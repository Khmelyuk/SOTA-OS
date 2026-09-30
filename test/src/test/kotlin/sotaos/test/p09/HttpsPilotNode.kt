package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.memory.Event
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.*
import sotaos.persistence.db.SotaOsDatabase
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*
import java.nio.file.Files
import java.util.UUID

/** Separate SQLite files, governed peer/key enrollment, private signing keys owned by the test host. */
internal class HttpsPilotNode(name: String) : AutoCloseable {
    val id = SotaId(name)
    val actor = SubjectRef.Person(PersonId("author-$name"))
    val signer = Ed25519EventSigner.generate()
    val path = Files.createTempFile("p09-https-$name-", ".db")
    private var driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    var store: SqlDelightStore
    val runtime get() = P09Runtime(store, LocalSyncIdentity(id, setOf(actor)), governance,
        Clock { now }, IdGenerator { UUID.randomUUID().toString() })
    val journal get() = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())

    init {
        SotaOsDatabase.Schema.create(driver)
        store = SqlDelightStore(driver)
    }

    fun enroll(other: HttpsPilotNode): String {
        val authority = Authority(authorityId, SubjectRef.Core(CoreId("governing-core")), operator,
            Scope(setOf("peer.enroll", "peer.rotate", "peer.revoke", "key.enroll"),
                setOf("peer:${other.id.value}", actorKeyTarget(actor), actorKeyTarget(other.actor))),
            governance, AuthorityBasis(note = "Explicit pilot bootstrap"), Validity(now.minusSeconds(1), null),
            SubjectRef.Core(CoreId("governing-core")), LifecycleState.ACTIVE)
        SqlDelightRepositories(store.database).authorities.save(authority)
        val root = runtime
        listOf(this, other).forEach { node ->
            root.keyProvisioning.enroll(invocation, authorityId,
                ActorSigningKeyRecord(node.actor, "key-${node.id.value}", node.signer.publicKeyEncoded()))
        }
        return root.peerProvisioning.enroll(invocation, authorityId, PeerTrustPolicy(other.id, setOf(other.actor),
            setOf(context), SyncDirection.entries.toSet(), setOf(id, other.id), setOf(id, other.id)))
    }

    fun record(name: String): SyncRecord {
        val event = Event(EventId(name), "SIGNED_PILOT_FACT", actor, now, context, "pilot-authority", null,
            mapOf("fact" to name), null, Provenance(null, actor, now), "", null)
        return runtime.integrity.sign(SyncRecord(event, id), signer).also(runtime::recordLocal)
    }

    fun reopen() {
        store.close()
        driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        store = SqlDelightStore(driver)
    }

    override fun close() {
        store.close()
        Files.deleteIfExists(path)
    }
}
