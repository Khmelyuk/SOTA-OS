package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.memory.SignedP07Runtime
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.LocalSyncIdentity
import sotaos.test.fakes.TestRightsConstraint
import java.time.Instant
import java.util.UUID

class HistoricalMigrationBehaviorTest : FunSpec({
    test("migrated Core Loop supports fresh decisions and Knowledge revision without rewriting unsigned history") {
        for (schema in HistoricalSchema.entries) HistoricalFixture(schema).use { f ->
            var store = f.open()
            var repos = SqlDelightRepositories(store.database)
            val old = requireNotNull(repos.decisions.findById(DecisionId("old-decision")))
            val events = repos.events.findByActor(migrationActor)
            val journal = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec()).records()
            journal.single().event shouldBe events.single()
            val service = MissionActionService(repos.missions, repos.decisions, repos.actions, repos.results,
                repos.authorities, repos.events, AuthorityLineage(repos.authorities)::isValid,
                TestRightsConstraint, migrationClock, migrationIds)
            shouldThrow<ActionNotAuthorizedException> { service.execute(migrationInvocation, old) }
            val fresh = service.decide(migrationInvocation, migrationActor, MissionId("mission"),
                AuthorityId("root"), "execute")
            service.execute(migrationInvocation, fresh).mode shouldBe Mode.PROTECTION
            val previous = requireNotNull(repos.knowledge.findById(KnowledgeId("knowledge-child")))
            val source = requireNotNull(repos.experiences.findById(ExperienceId("experience")))
            val memory = MemoryService(repos.experiences, repos.knowledge, migrationClock, migrationIds,
                TestRightsConstraint, repos.events)
            val next = memory.reviseKnowledge(migrationInvocation, previous, source, "New conclusion", "New review")
            next.version shouldBe 5
            next.supersedes shouldBe previous.id
            val signed = SignedP07Runtime(store, LocalSyncIdentity(SotaId("fixture-node"), setOf(migrationActor)),
                Context("governance"), migrationClock, migrationIds,
                { error("Legacy source must fail before signing") })
            shouldThrow<IllegalArgumentException> {
                signed.proposeKnowledgeCandidate(migrationInvocation, source, "Not signed history")
            }
            store = f.reopen()
            repos = SqlDelightRepositories(store.database)
            repos.decisions.findById(old.id) shouldBe old
            repos.knowledge.findById(previous.id) shouldBe previous
            repos.knowledge.findById(next.id) shouldBe next
            repos.events.findById(events.single().id) shouldBe events.single()
            SqlDelightSyncRepository(store.database, JsonSyncRecordCodec()).records() shouldBe journal
        }
    }

    test("historical lockout verifiers revocation and credential attribution retain their meaning") {
        for (schema in HistoricalSchema.entries) HistoricalFixture(schema).use { f ->
            val store = f.open()
            val repository = SqlDelightAuthenticationRepository(store.database)
            val provider = LocalPassphraseAuthenticationProvider(repository)
            val unit = SotaId("fixture-node")
            var now = Instant.parse("2026-09-01T00:00:30Z")
            val auth = AuthenticationService(listOf(provider), repository,
                mapOf(unit to setOf(provider.providerId)), { now })
            fun login() = auth.complete(unit, auth.begin(unit, provider.providerId, "fixture"),
                "historical-test-passphrase".toCharArray())
            shouldThrow<AuthenticationDeniedException> { login() }
            now = migrationClock.now()
            if (schema == HistoricalSchema.DURABLE_P09) {
                login().person shouldBe migrationActor.id
                repository.findLocalCredential(unit, "fixture")?.revision shouldBe 1L
                f.sql { it.query("SELECT actor_person_id FROM local_credential_audit") } shouldBe listOf(listOf(null))
            } else {
                shouldThrow<AuthenticationDeniedException> { login() }
                repository.findLocalCredential(unit, "fixture")?.revision shouldBe 3L
                repository.findLocalCredential(unit, "fixture")?.revokedAt shouldBe
                    Instant.parse("2026-09-02T00:00:00Z")
            }
        }
    }

    test("post-upgrade governance and history guards reject rewriting or reviving historical records") {
        for (schema in HistoricalSchema.entries) HistoricalFixture(schema).use { f ->
            val repos = SqlDelightRepositories(f.open().database)
            val forbidden = listOf("UPDATE event SET payload_json = '{}'", "DELETE FROM event",
                "UPDATE sync_journal SET record_json = '{}'", "DELETE FROM sync_journal",
                "UPDATE consent SET revoked_at = NULL",
                "UPDATE authority SET parent_authority_id = 'invented'",
                "UPDATE decision SET mission_snapshot_json = '{}'", "DELETE FROM local_credential_audit")
            forbidden.forEach { statement -> shouldThrow<Exception> { f.execute(statement) } }
            val previous = requireNotNull(repos.knowledge.findById(KnowledgeId("knowledge-child")))
            shouldThrow<IllegalArgumentException> { repos.knowledge.save(previous.copy(statement = "Rewritten")) }
            if (schema == HistoricalSchema.KNOWLEDGE_VERSIONS) {
                val child = requireNotNull(repos.authorities.findById(AuthorityId("withdrawn-child")))
                child.parentAuthorityId shouldBe AuthorityId("root")
                child.state shouldBe LifecycleState.REVOKED
                val permitted = AuthorityLineage(repos.authorities)
                    .isValid(child, Scope(setOf("execute")), migrationClock.now())
                permitted shouldBe false
                shouldThrow<Exception> { f.execute("DELETE FROM authority_revocation") }
            }
            f.reopen()
            f.sql { it.query("PRAGMA integrity_check") } shouldBe listOf(listOf("ok"))
        }
    }
})

private val migrationActor = SubjectRef.Person(PersonId("actor"))
private val migrationClock = Clock { Instant.parse("2026-11-23T00:00:00Z") }
private val migrationIds = IdGenerator { UUID.randomUUID().toString() }
private val migrationInvocation = ProtocolInvocation(migrationActor, "Historical execution",
    Context("work", description = "Frozen migration fixture"))
