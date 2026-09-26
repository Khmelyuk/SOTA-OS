package sotaos.api.sync

import sotaos.application.ports.*
import sotaos.application.services.ProvisioningAuthorization
import sotaos.application.sync.SyncTransport
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.*
import sotaos.protocol.*
import sotaos.security.*
import sotaos.sync.*

/** Local composition root. Hosts must authenticate local operators before invoking provisioning. */
class P09Runtime(
    store: SqlDelightStore,
    local: LocalSyncIdentity,
    governanceContext: Context,
    clock: Clock,
    ids: IdGenerator,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) {
    private val repositories = SqlDelightRepositories(store.database)
    private val peers = SqlDelightPeerTrustRepository(store.database)
    private val codec = JsonSyncRecordCodec()
    val integrity = SyncRecordIntegrity(codec)
    private val admission = ProductionPeerAdmission(peers, repositories.actorSigningKeys, local, integrity)
    private val service = SyncService(
        local.node, SqlDelightSyncRepository(store.database, codec), codec, admission, ids
    )
    private val endpoint = SyncEndpoint(service, JsonSyncMessageCodec())
    private val authenticator = RegistryPeerAuthenticator(peers)
    private val authorization = ProvisioningAuthorization(repositories.authorities, clock, governanceContext, rights)
    val peerProvisioning = PeerProvisioningService(peers, authorization, local.node)
    val keyProvisioning = ActorKeyProvisioningService(repositories.actorSigningKeys, peers, authorization)

    /** Credential, registry, key snapshot, admission and journal commit share the SQLite transaction. */
    fun exchange(authorizationHeader: String, body: String): String = peers.transaction {
        endpoint.exchange(authorizationHeader, body, authenticator::authenticate)
    }

    /** Only locally authorized producers may supply records; strict admission applies before export. */
    fun recordLocal(record: SyncRecord) = service.recordLocal(record)

    fun synchronize(peer: SotaId, transport: SyncTransport) = service.synchronize(peer, transport)
}
