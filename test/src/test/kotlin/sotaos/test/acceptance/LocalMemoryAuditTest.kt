package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.MemoryService
import sotaos.domain.memory.*
import sotaos.domain.shared.*
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

class LocalMemoryAuditTest : FunSpec({
    test("ordinary memory records complete lifecycle snapshots actor purpose and exact reason across reopen") {
        SignedActionFixture().use { f ->
            val service = f.auditService()
            val source = f.auditSource()
            val experience = service.createExperience(f.invocation, listOf(source), "Conclusion", "Conditions")
            val candidate = service.proposeKnowledgeCandidate(f.invocation, experience, "Original statement")
            val validator = SubjectRef.Core(CoreId("core"))
            val validated = service.validateKnowledge(f.invocation.copy(actor = validator), candidate, validator)
            val reason = "New evidence\nPreserve exact wording."
            val deprecated = service.deprecate(f.invocation, validated, reason)
            val authorEvents = f.repos.events.findByActor(f.actor).filter { it.type.startsWith("LOCAL_") }
            authorEvents.map { it.type }.toSet() shouldBe setOf("LOCAL_EXPERIENCE_RECORDED",
                "LOCAL_KNOWLEDGE_PROPOSED", "LOCAL_KNOWLEDGE_DEPRECATED")
            val validation = f.repos.events.findByActor(validator).single()
            validation.type shouldBe "LOCAL_KNOWLEDGE_VALIDATED"
            validation.provenance.author shouldBe validator
            validation.context shouldBe candidate.context
            validation.payload["purpose"] shouldBe f.invocation.purpose
            validation.payload["entityId"] shouldBe candidate.id.value
            val before = validation.payload["before"] as Map<*, *>
            val after = validation.payload["after"] as Map<*, *>
            before["status"] shouldBe "CANDIDATE"
            after["status"] shouldBe "VALIDATED"
            after["statement"] shouldBe candidate.statement
            after["provenance"] shouldBe before["provenance"]
            after["knowledgeVersion"] shouldBe 1L
            val withdrawal = authorEvents.single { it.type == "LOCAL_KNOWLEDGE_DEPRECATED" }
            withdrawal.payload["reason"] shouldBe reason
            (withdrawal.payload["before"] as Map<*, *>)["status"] shouldBe "VALIDATED"
            (withdrawal.payload["after"] as Map<*, *>)["status"] shouldBe "DEPRECATED"
            for (event in authorEvents + validation) {
                event.signature shouldBe null
                event.timestamp shouldBe f.now
                event.contentHash.length shouldBe 64
            }
            f.reopen()
            f.repos.knowledge.findById(candidate.id) shouldBe deprecated
            (authorEvents + validation).forEach { f.repos.events.findById(it.id) shouldBe it }
            f.count("event") shouldBe 5L
            f.count("sync_journal") shouldBe 0L
            f.count("verified_sync_record") shouldBe 0L
            shouldThrow<Exception> { f.repos.events.append(validation) }
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM event", 0) }
        }
    }

    test("event and entity write failures roll back every ordinary memory operation and permit retry after reopen") {
        for (operation in listOf("experience", "propose", "validate", "deprecate")) {
            for (failureTarget in listOf("event", "entity")) SignedActionFixture().use { f ->
                val service = f.auditService()
                val source = f.auditSource()
                val experience = if (operation == "experience") null else
                    service.createExperience(f.invocation, listOf(source), "Conclusion", "Conditions")
                val candidate = if (operation in setOf("validate", "deprecate"))
                    service.proposeKnowledgeCandidate(f.invocation, requireNotNull(experience), "Statement") else null
                val before = f.repos.events.findByActor(f.actor)
                val table = if (failureTarget == "event") "event" else
                    if (operation == "experience") "experience" else "knowledge"
                val verb = if (table == "knowledge" && candidate != null) "UPDATE" else "INSERT"
                f.driver.execute(null, "CREATE TRIGGER audit_failure BEFORE $verb ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END", 0)
                fun invoke(runtime: MemoryService) {
                    when (operation) {
                        "experience" -> runtime.createExperience(f.invocation, listOf(source), "Result", "Conditions")
                        "propose" -> runtime.proposeKnowledgeCandidate(f.invocation, requireNotNull(experience), "New")
                        "validate" -> runtime.validateKnowledge(f.invocation, requireNotNull(candidate), f.actor)
                        else -> runtime.deprecate(f.invocation, requireNotNull(candidate), "Reason")
                    }
                }
                shouldThrow<Exception> { invoke(service) }
                f.reopen()
                f.repos.events.findByActor(f.actor) shouldBe before
                f.count("experience") shouldBe if (experience == null) 0L else 1L
                f.count("knowledge") shouldBe if (candidate == null) 0L else 1L
                candidate?.let { f.repos.knowledge.findById(it.id) shouldBe it }
                f.driver.execute(null, "DROP TRIGGER audit_failure", 0)
                invoke(f.auditService())
                f.count("event") shouldBe before.size.toLong() + 1L
            }
        }
    }

    test("rights denial and stale lifecycle inputs add no audit events") {
        SignedActionFixture().use { f ->
            val service = f.auditService()
            val experience = service.createExperience(f.invocation, listOf(f.auditSource()), "Result", "Conditions")
            val candidate = service.proposeKnowledgeCandidate(f.invocation, experience, "Statement")
            val validated = service.validateKnowledge(f.invocation, candidate, f.actor)
            val before = f.repos.events.findByActor(f.actor)
            val denied = f.auditService(rights = RightsConstraint {
                throw RightsConstraintViolation("DENIED", "Policy")
            })
            shouldThrow<RightsConstraintViolation> { denied.deprecate(f.invocation, validated, "Denied") }
            shouldThrow<IllegalArgumentException> { service.deprecate(f.invocation, candidate, "Stale") }
            f.repos.events.findByActor(f.actor) shouldBe before
            f.repos.knowledge.findById(candidate.id) shouldBe validated
        }
    }

    test("audit Event ID collision prevents a partial Experience write") {
        SignedActionFixture().use { f ->
            val source = f.auditSource()
            val ids = ArrayDeque(listOf("new-experience", source.id.value))
            val service = f.auditService(ids = IdGenerator { ids.removeFirst() })
            shouldThrow<IllegalArgumentException> {
                service.createExperience(f.invocation, listOf(source), "Conclusion", "Conditions")
            }
            f.reopen()
            f.count("experience") shouldBe 0L
            f.repos.events.findByActor(f.actor) shouldBe listOf(source)
        }
    }

    test("legacy Knowledge gets its first transition audit without fabricating earlier history") {
        SignedActionFixture().use { f ->
            val source = f.auditSource()
            val experience = f.repos.experiences.save(Experience(ExperienceId("legacy-exp"), listOf(source.id),
                f.context, "Work", "Done", "Conclusion", "Conditions", Confidence.MEDIUM, source.provenance))
            val legacy = f.repos.knowledge.save(Knowledge(KnowledgeId("legacy"), "Legacy statement",
                listOf(experience.id), emptyList(), f.context, KnowledgeStatus.VALIDATED, 3, null, source.provenance))
            f.auditService().deprecate(f.invocation, legacy, "Legacy withdrawal")
            f.reopen()
            val audit = f.repos.events.findByActor(f.actor).single { it.type.startsWith("LOCAL_") }
            audit.type shouldBe "LOCAL_KNOWLEDGE_DEPRECATED"
            (audit.payload["before"] as Map<*, *>)["statement"] shouldBe legacy.statement
            (audit.payload["before"] as Map<*, *>)["knowledgeVersion"] shouldBe 3L
            audit.payload["reason"] shouldBe "Legacy withdrawal"
            f.count("event") shouldBe 2L
        }
    }
})

private fun SignedActionFixture.auditService(rights: RightsConstraint = TestRightsConstraint,
    ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() }) =
    MemoryService(repos.experiences, repos.knowledge, Clock { now }, ids, rights, repos.events)

private fun SignedActionFixture.auditSource(): Event = repos.events.append(Event(EventId("audit-source"),
    "OBSERVATION", actor, now, context, null, null, mapOf("observed" to "value"), null,
    Provenance(null, actor, now), "legacy-hash", null))
