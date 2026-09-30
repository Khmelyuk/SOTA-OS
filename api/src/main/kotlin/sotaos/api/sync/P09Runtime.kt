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
    private val store: SqlDelightStore,
    private val local: LocalSyncIdentity,
    governanceContext: Context,
    clock: Clock,
    ids: IdGenerator,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) {
    val nodeId: SotaId get() = local.node
    private val repositories = SqlDelightRepositories(store.database)
    private val peerStore = SqlDelightPeerTrustRepository(store.database)
    private val peers = object : PeerTrustRepository by peerStore {
        override fun <T> transaction(block: () -> T): T = store.withLocalAccess { peerStore.transaction(block) }
    }
    private val codec = JsonSyncRecordCodec()
    private val approvals = SqlDelightHistoricalApprovalRepository(store.database)
    val integrity = SyncRecordIntegrity(codec)
    private val signatures = VerifiedRecordSignatures(repositories.actorSigningKeys,
        SqlDelightVerifiedRecordRepository(store.database), codec, integrity, clock, approvals)
    private val admission = ProductionPeerAdmission(peers, repositories.actorSigningKeys, local, integrity, signatures)
    private val service = SyncService(
        local.node, SqlDelightSyncRepository(store.database, codec, verifyAppended = signatures::remember),
        codec, admission, ids
    )
    private val endpoint = SyncEndpoint(service, JsonSyncMessageCodec())
    private val authenticator = RegistryPeerAuthenticator(peers)
    private val authorization = ProvisioningAuthorization(repositories.authorities, clock, governanceContext, rights)
    val historicalApprovals = HistoricalRecordApprovalService(
        approvals, peers, authorization, codec, integrity, local.node)
    val peerProvisioning = PeerProvisioningService(peers, authorization, local.node)
    val keyProvisioning = ActorKeyProvisioningService(repositories.actorSigningKeys, peers, authorization)

    /** Credential, registry, key snapshot, admission and journal commit share the SQLite transaction. */
    fun exchange(authorizationHeader: String, body: String): String = store.withLocalAccess {
        peers.transaction { endpoint.exchange(authorizationHeader, body, authenticator::authenticate) }
    }

    /** Only locally authorized producers may supply records; strict admission applies before export. */
    fun recordLocal(record: SyncRecord) = store.withLocalAccess { peers.transaction {
        val event = record.event
        require(event.actor in local.actors && event.actor !is SubjectRef.Agent)
        require(event.provenance.author == event.actor && event.provenance.recordedAt == event.timestamp)
        require(event.provenance.sourceEventId == null || event.provenance.sourceEventId in record.parents)
        record.assertion?.let { require(it.entity in local.assertionEntities && it.context == event.context) }
        service.recordLocal(record)
    } }

    fun synchronize(peer: SotaId, transport: SyncTransport): sotaos.application.sync.PeerCheckpoint {
        val request = store.withLocalAccess { service.prepareExchange(peer) }
        val response = transport.exchange(peer, request)
        return store.withLocalAccess { service.completeExchange(peer, request, response) }
    }
}
