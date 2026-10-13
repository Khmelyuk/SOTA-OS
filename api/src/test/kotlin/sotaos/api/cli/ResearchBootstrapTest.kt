package sotaos.api.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.AuthenticatedSession
import sotaos.domain.identity.Person
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.db.SotaOsDatabase
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.time.Instant

class ResearchBootstrapTest : FunSpec({
    test("setup atomically records new Core membership bounded human authority and audit") {
        ResearchBootstrapFixture().use { f ->
            val id = setupResearch(f.repos, f.session, f.rights)
            val authority = requireNotNull(f.repos.authorities.findById(id))
            authority.subject shouldBe SubjectRef.Person(f.session.person)
            authority.scope.resources shouldBe setOf(sotaos.agent.research.ResearchAgent.RESOURCE,
                sotaos.agent.research.ResearchAuthorization.unitResource(f.session.unit))
            (authority.issuer is SubjectRef.Core) shouldBe true
            authority.scope.actions.any { "publish" in it || "execute" in it } shouldBe false
            (authority.validity.until != null) shouldBe true
            val event = f.repos.events.findByActor(authority.subject).single()
            event.type shouldBe "RESEARCH_WORKSPACE_SETUP"
            event.authorityRef shouldBe id.value
            event.payload["unitId"] shouldBe f.session.unit.value
            event.contentHash.length shouldBe 64
        }
    }
    test("failed audit insertion rolls back the Core membership and authority as one transaction") {
        ResearchBootstrapFixture().use { f ->
            f.driver.execute(null, "CREATE TRIGGER fail_setup BEFORE INSERT ON event " +
                "BEGIN SELECT RAISE(ABORT, 'setup failure'); END", 0)
            shouldThrow<Exception> { setupResearch(f.repos, f.session, f.rights) }
            listOf("core", "membership", "authority", "event").forEach { f.count(it) shouldBe 0L }
        }
    }
    test("surrender audit failure rolls back grant and descendant revocations") {
        ResearchBootstrapFixture().use { f ->
            val id = setupResearch(f.repos, f.session, f.rights)
            val root = requireNotNull(f.repos.authorities.findById(id))
            f.repos.authorities.save(root.copy(id = AuthorityId("child"), issuer = root.subject,
                subject = SubjectRef.Person(PersonId("other")), parentAuthorityId = id))
            f.driver.execute(null, "CREATE TRIGGER fail_surrender BEFORE INSERT ON event " +
                "BEGIN SELECT RAISE(ABORT, 'surrender failure'); END", 0)
            shouldThrow<Exception> { surrenderResearch(f.repos, f.session, id, f.rights) }
            f.repos.authorities.findById(id)?.state shouldBe LifecycleState.ACTIVE
            f.repos.authorities.findById(AuthorityId("child"))?.state shouldBe LifecycleState.ACTIVE
            f.count("event") shouldBe 1L
        }
    }
    test("surrender rejects another Person or Sota unit and cascades only the owner's research grant") {
        ResearchBootstrapFixture().use { f ->
            val id = setupResearch(f.repos, f.session, f.rights)
            val root = requireNotNull(f.repos.authorities.findById(id))
            shouldThrow<IllegalArgumentException> {
                surrenderResearch(f.repos, f.session.copy(person = PersonId("other")), id, f.rights)
            }
            shouldThrow<IllegalArgumentException> {
                surrenderResearch(f.repos, f.session.copy(unit = SotaId("other-unit")), id, f.rights)
            }
            f.repos.authorities.save(root.copy(id = AuthorityId("child"), issuer = root.subject,
                subject = SubjectRef.Person(PersonId("other")), parentAuthorityId = id))
            surrenderResearch(f.repos, f.session, id, f.rights)
            f.repos.authorities.findById(id)?.state shouldBe LifecycleState.REVOKED
            f.repos.authorities.findById(AuthorityId("child"))?.state shouldBe LifecycleState.REVOKED
            f.repos.events.findByActor(root.subject).last().type shouldBe "RESEARCH_AUTHORITY_SURRENDERED"
        }
    }
})

private class ResearchBootstrapFixture : AutoCloseable {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = SotaOsDatabase(driver)
    val repos = SqlDelightRepositories(db)
    val rights = RightsConstraintDecorator(listOf(NoAgentActionRule))
    val session = AuthenticatedSession(SotaId("local"), PersonId("researcher"), "local-passphrase", Instant.now())
    init {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        SotaOsDatabase.Schema.create(driver)
        repos.persons.save(Person(session.person, emptyList(), Instant.now()))
    }
    fun count(table: String): Long = driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { cursor ->
        cursor.next()
        app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
    }, 0).value
    override fun close() = driver.close()
}
