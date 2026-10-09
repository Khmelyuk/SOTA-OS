package sotaos.test.acceptance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.api.agency.SignedP05Runtime
import sotaos.application.ports.*
import sotaos.application.sync.SyncDirection
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.persistence.db.SotaOsDatabase
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

internal class SignedActionFixture : AutoCloseable {
    val path = Files.createTempFile("signed-action-", ".db")
    var driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    var store: SqlDelightStore
    val now: Instant = Instant.parse("2026-10-09T00:00:00Z")
    val actor = SubjectRef.Person(PersonId("actor"))
    val context = Context("work")
    val invocation = ProtocolInvocation(actor, "Execute authorized work", context)
    val authorityId = AuthorityId("work-authority")
    val signer = Ed25519EventSigner.generate()
    val repos get() = SqlDelightRepositories(store.database)
    val journal get() = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())

    init {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        SotaOsDatabase.Schema.create(driver)
        store = SqlDelightStore(driver)
        repos.persons.save(sotaos.domain.identity.Person(actor.id, emptyList(), now))
        repos.cores.save(sotaos.domain.collective.Core(CoreId("core"), "Core", now, LifecycleState.ACTIVE))
        repos.authorities.save(Authority(authorityId, SubjectRef.Core(CoreId("core")), actor,
            Scope(setOf("execute")), context, AuthorityBasis(note = "trusted fixture"),
            Validity(now.minusSeconds(1), null), SubjectRef.Core(CoreId("core")), LifecycleState.ACTIVE))
    }

    fun key(key: Ed25519EventSigner = signer) {
        // Trusted fixture only; production provisioning requires independent authority.
        repos.actorSigningKeys.save(ActorSigningKeyRecord(actor, "action-key", key.publicKeyEncoded()), now)
    }

    fun runtime(node: String = "source", signingKey: EventSigner = signer,
        actors: Set<SubjectRef> = setOf(actor)) = SignedP05Runtime(store,
        LocalSyncIdentity(SotaId(node), actors), Context("governance"), Clock { now },
        IdGenerator { UUID.randomUUID().toString() }, { signingKey })

    fun decision(runtime: SignedP05Runtime, affected: List<PersonId> = emptyList()) = runtime.decide(
        invocation, actor, runtime.formMission(invocation, actor, "Work", Mode.CREATION).id,
        authorityId, "execute", affected)

    fun peer(peer: String): String {
        val token = PeerCredentials.generate()
        SqlDelightPeerTrustRepository(store.database).save(TrustedPeer(PeerTrustPolicy(SotaId(peer),
            setOf(actor), setOf(context), SyncDirection.entries.toSet(), setOf(SotaId("source")),
            setOf(SotaId("source"))), PeerCredentials.hash(token)))
        return token
    }

    fun count(table: String): Long = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    fun reopen() {
        store.close()
        driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        store = SqlDelightStore(driver)
    }

    override fun close() {
        store.close()
        Files.deleteIfExists(path)
    }
}
