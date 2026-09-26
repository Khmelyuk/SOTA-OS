package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.memory.Event
import sotaos.domain.relation.*
import sotaos.domain.relation.Authority
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.*
import sotaos.persistence.db.SotaOsDatabase
import sotaos.protocol.*
import sotaos.security.*
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

internal val now: Instant = Instant.parse("2026-09-26T00:00:00Z")
internal val context = Context("shared")
internal val governance = Context("peer-governance")
internal val operator = SubjectRef.Person(PersonId("operator"))
internal val remoteActor = SubjectRef.Person(PersonId("remote-author"))
internal val localActor = SubjectRef.Person(PersonId("local-author"))
internal val localId = SotaId("local")
internal val peerId = SotaId("remote")
internal val authorityId = AuthorityId("provisioning")
internal val invocation = ProtocolInvocation(operator, "Manage trusted sync", governance)
internal val messages = JsonSyncMessageCodec()

internal class ProductionFixture : AutoCloseable {
    val path = Files.createTempFile("p09-production-", ".db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    val store: SqlDelightStore
    val repositories: SqlDelightRepositories
    val peers: SqlDelightPeerTrustRepository
    val runtime: P09Runtime
    val signer = Ed25519EventSigner.generate()
    val policy = PeerTrustPolicy(peerId, setOf(remoteActor), setOf(context), SyncDirection.entries.toSet(),
        setOf(peerId), setOf(localId, peerId))
    val authority = Authority(authorityId, SubjectRef.Core(CoreId("governing-core")), operator,
        Scope(setOf("peer.enroll", "peer.rotate", "peer.revoke", "key.enroll", "key.rotate", "key.revoke"),
            setOf("peer:remote", actorKeyTarget(remoteActor), actorKeyTarget(localActor))),
        governance, AuthorityBasis(note = "Explicit test bootstrap"), Validity(now.minusSeconds(1), null),
        SubjectRef.Core(CoreId("governing-core")), LifecycleState.ACTIVE)

    init {
        SotaOsDatabase.Schema.create(driver)
        store = SqlDelightStore(driver)
        repositories = SqlDelightRepositories(store.database)
        peers = SqlDelightPeerTrustRepository(store.database)
        repositories.authorities.save(authority)
        runtime = runtime(store)
    }

    fun enroll(trust: PeerTrustPolicy = policy): String {
        runtime.keyProvisioning.enroll(invocation, authorityId,
            ActorSigningKeyRecord(remoteActor, "remote-key-1", signer.publicKeyEncoded()))
        return runtime.peerProvisioning.enroll(invocation, authorityId, trust)
    }

    fun record(id: String = "event-1"): SyncRecord {
        val event = Event(EventId(id), "SIGNED_FACT", remoteActor, now, context, "authority-ref", null,
            mapOf("fact" to "original"), null, Provenance(null, remoteActor, now), "", null)
        return runtime.integrity.sign(SyncRecord(event, peerId), signer)
    }

    fun exchange(token: String, record: SyncRecord, receiver: P09Runtime = runtime): SyncResponse {
        val request = SyncRequest("request-1", peerId, 0, SyncBatch(0, 1, listOf(record)))
        return messages.decodeResponse(receiver.exchange("Bearer $token", messages.encodeRequest(request)))
    }

    override fun close() {
        store.close()
        Files.deleteIfExists(path)
    }
}

internal fun runtime(store: SqlDelightStore): P09Runtime = P09Runtime(
    store, LocalSyncIdentity(localId, setOf(localActor)), governance, Clock { now },
    IdGenerator { UUID.randomUUID().toString() }
)
