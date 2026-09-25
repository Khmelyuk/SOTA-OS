package sotaos.test.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.application.services.AuthorityService
import sotaos.application.services.CollectiveService
import sotaos.application.services.IdentityService
import sotaos.application.services.MemoryService
import sotaos.application.services.MissionActionService
import sotaos.domain.agency.Outcome
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator

private val testRights = RightsConstraintDecorator(listOf(NoAgentActionRule))

class SqlDelightCoreLoopIntegrationTest : FunSpec({
    test("authorized Core Loop records survive closing and reopening SQLite") {
        val databasePath = Files.createTempFile("sota-os-core-loop", ".db")
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = Clock { now }
        val ids = IdGenerator { UUID.randomUUID().toString() }

        try {
            val persisted = createCoreLoop(databasePath.toString(), clock, ids)
            val driver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")
            SqlDelightStore(driver).use { store ->
                val repositories = SqlDelightRepositories(store.database)
                repositories.persons.findById(persisted.personId) shouldBe persisted.person.copy(
                    identities = listOf(persisted.identity.id)
                )
                repositories.identities.findByPerson(persisted.personId).single() shouldBe persisted.identity
                repositories.cores.findById(persisted.core.id) shouldBe persisted.core
                repositories.missions.findById(persisted.mission.id) shouldBe persisted.mission
                repositories.authorities.findById(persisted.authority.id) shouldBe persisted.authority
                repositories.events.findById(persisted.event.id) shouldBe persisted.event
                repositories.knowledge.findById(persisted.knowledge.id) shouldBe persisted.knowledge
            }
        } finally {
            Files.deleteIfExists(databasePath)
            Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-wal"))
            Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}-shm"))
        }
    }
})

private data class PersistedCoreLoop(
    val personId: PersonId,
    val person: sotaos.domain.identity.Person,
    val identity: sotaos.domain.identity.Identity,
    val core: sotaos.domain.collective.Core,
    val mission: sotaos.domain.agency.Mission,
    val authority: sotaos.domain.relation.Authority,
    val event: sotaos.domain.memory.Event,
    val knowledge: sotaos.domain.memory.Knowledge
)

private fun createCoreLoop(databasePath: String, clock: Clock, ids: IdGenerator): PersistedCoreLoop {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$databasePath")
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    SotaOsDatabase.Schema.create(driver)
    SqlDelightStore(driver).use { store ->
        return persistCoreLoop(store, clock, ids)
    }
}

private fun persistCoreLoop(store: SqlDelightStore, clock: Clock, ids: IdGenerator): PersistedCoreLoop {
    val repositories = SqlDelightRepositories(store.database)
    val identityService = IdentityService(repositories.persons, repositories.identities, clock, ids, testRights)
    val person = identityService.createPerson()
    val personInvocation = ProtocolInvocation(SubjectRef.Person(person.id), "integration fixture")
    val identity = identityService.createIdentity(personInvocation, person.id, "integration-founder")
    val authorityService = AuthorityService(repositories.authorities, clock, ids, testRights)
    val collectiveService = CollectiveService(
        repositories.cores, repositories.memberships, repositories.authorities,
        authorityService::isValid, clock, ids, testRights
    )
    val core = collectiveService.createCore(personInvocation, "SQLite Integration Core", person.id)
    val context = Context(domain = "sqlite-integration")
    val authority = authorityService.grant(
        invocation = ProtocolInvocation(SubjectRef.Core(core.id), "integration authority fixture"),
        issuer = SubjectRef.Core(core.id),
        subject = SubjectRef.Person(person.id),
        scope = Scope(actions = setOf("integration-action")),
        context = context,
        basis = AuthorityBasis(note = "Integration run authority"),
        validity = Validity(clock.now(), null),
        accountableTo = SubjectRef.Core(core.id)
    )
    val missionActions = MissionActionService(
        repositories.missions,
        repositories.decisions,
        repositories.actions,
        repositories.results,
        repositories.authorities,
        repositories.events,
        authorityService::isValid,
        RightsConstraintDecorator(listOf(NoAgentActionRule)),
        clock,
        ids
    )
    val mission = missionActions.formMission(
        ProtocolInvocation(SubjectRef.Core(core.id), "integration mission fixture"), SubjectRef.Core(core.id),
        "Verify persistent Core Loop", Mode.CREATION
    )
    val decision = missionActions.decide(
        personInvocation, SubjectRef.Person(person.id), mission.id, authority.id, "integration-action", emptyList()
    )
    val action = missionActions.execute(personInvocation, decision)
    missionActions.recordResult(personInvocation, action, Outcome.SUCCESS, "SQLite write succeeded")
    val event = repositories.events.findByActor(SubjectRef.Person(person.id)).single()
    val memoryService = MemoryService(repositories.experiences, repositories.knowledge, clock, ids, testRights)
    val experience = memoryService.createExperience(
        personInvocation, listOf(event), "SQLite-backed action completed", "Local integration verification"
    )
    val knowledge = memoryService.proposeKnowledgeCandidate(
        personInvocation, experience, "The SQLite Core Loop persists across connections"
    )
    repositories.events.findById(event.id) shouldNotBe null
    return PersistedCoreLoop(person.id, person, identity, core, mission, authority, event, knowledge)
}
