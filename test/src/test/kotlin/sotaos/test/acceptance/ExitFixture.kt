package sotaos.test.acceptance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.api.exit.P10Runtime
import sotaos.application.ports.*
import sotaos.domain.collective.Core
import sotaos.domain.collective.Membership
import sotaos.domain.identity.Person
import sotaos.domain.identity.Identity
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

internal class ExitFixture : AutoCloseable {
    val path = Files.createTempFile("p10-", ".db")
    var driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    var store: SqlDelightStore
    val now: Instant = Instant.parse("2026-09-26T00:00:00Z")
    val person = PersonId("leaving-person")
    val core = CoreId("chosen")
    val other = CoreId("other")
    val target = ExitTarget(person, core)
    val otherTarget = ExitTarget(person, other)
    val invocation = ProtocolInvocation(SubjectRef.Person(person), "Retain my outstanding responsibilities",
        Context("exit"))
    val repos get() = SqlDelightRepositories(store.database)
    val exits get() = SqlDelightExitRepository(store.database)
    val scope get() = SqlDelightExitScopeRepository(store.database)
    val inventory get() = SqlDelightExitInventoryRepository(store.database)
    val runtime get() = P10Runtime(store, Clock { now }, IdGenerator { UUID.randomUUID().toString() })

    init {
        SotaOsDatabase.Schema.create(driver)
        store = SqlDelightStore(driver)
        repos.persons.save(Person(person, emptyList(), now))
        repos.identities.save(Identity(IdentityId("identity"), person, "my-handle"))
        listOf(core, other).forEach { id ->
            repos.cores.save(Core(id, id.value, now, LifecycleState.ACTIVE))
            repos.memberships.save(Membership(person, SubjectRef.Core(id), null, now, null, LifecycleState.ACTIVE))
            repos.authorities.save(authority(id))
            val exitTarget = ExitTarget(person, id)
            inventory.addRelation(CoreExitRelation("relation-${id.value}", exitTarget, "TRUST", "scoped trust"))
            inventory.addObligation(ExitObligation("obligation-${id.value}", exitTarget,
                "Still responsible", ObligationState.OPEN))
        }
    }

    fun authority(id: CoreId): Authority = Authority(AuthorityId("authority-${id.value}"),
        SubjectRef.Core(id), SubjectRef.Person(person), Scope(setOf("execute")), Context("work"),
        AuthorityBasis(note = "test bootstrap"), Validity(now.minusSeconds(1), null),
        SubjectRef.Core(id), LifecycleState.ACTIVE)

    fun reopen() {
        store.close()
        driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        store = SqlDelightStore(driver)
    }

    fun prepare(): String {
        val service = runtime.service
        val id = service.requestExit(invocation, core).id
        service.revokeActiveDelegations(invocation, id)
        service.closeRelations(invocation, id)
        service.settleObligations(invocation, id)
        return id
    }

    fun failOn(table: String) {
        driver.execute(null, "CREATE TRIGGER injected_failure BEFORE INSERT ON $table " +
            "BEGIN SELECT RAISE(ABORT, 'injected failure'); END", 0)
    }

    override fun close() {
        store.close()
        Files.deleteIfExists(path)
    }
}
