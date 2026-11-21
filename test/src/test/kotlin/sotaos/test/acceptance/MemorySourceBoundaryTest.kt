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

class MemorySourceBoundaryTest : FunSpec({
    test("ordinary Experience rejects unknown and changed Events before policy or writes") {
        SignedActionFixture().use { f ->
            val event = f.unsignedSource()
            var checked = 0
            val service = f.ordinaryMemory(rights = RightsConstraint {
                checked++
                RightsConstraintDecision(emptySet())
            })
            for (input in listOf(event.copy(id = EventId("missing")),
                event.copy(payload = mapOf("changed" to "value")), event.copy(contentHash = "changed"))) {
                shouldThrow<IllegalArgumentException> {
                    service.createExperience(f.invocation, listOf(input), "Denied", "Conditions")
                }
            }
            checked shouldBe 0
            f.count("experience") shouldBe 0L
        }
    }

    test("ordinary Experience rejects empty duplicate mixed-context and future source Events") {
        SignedActionFixture().use { f ->
            val event = f.unsignedSource()
            val other = f.repos.events.append(event.copy(id = EventId("other"), context = Context("other")))
            val future = f.repos.events.append(event.copy(id = EventId("future"), timestamp = f.now.plusSeconds(1)))
            for (input in listOf(emptyList(), listOf(event, event), listOf(event, other), listOf(future))) {
                shouldThrow<IllegalArgumentException> {
                    f.ordinaryMemory().createExperience(f.invocation, input, "Denied", "Conditions")
                }
            }
            f.count("experience") shouldBe 0L
        }
    }

    test("ordinary proposal requires exact persisted Experience before rights evaluation") {
        SignedActionFixture().use { f ->
            val experience = f.ordinaryExperience()
            var checked = 0
            val service = f.ordinaryMemory(rights = RightsConstraint {
                checked++
                RightsConstraintDecision(emptySet())
            })
            for (input in listOf(experience.copy(id = ExperienceId("missing")),
                experience.copy(conclusion = "Changed"), experience.copy(context = Context("bypass")))) {
                shouldThrow<IllegalArgumentException> {
                    service.proposeKnowledgeCandidate(f.invocation, input, "Denied")
                }
            }
            checked shouldBe 0
            f.count("knowledge") shouldBe 0L
        }
    }

    test("validation rejects absent and changed Knowledge and deprecation cannot forge the author") {
        SignedActionFixture().use { f ->
            val service = f.ordinaryMemory()
            val candidate = service.proposeKnowledgeCandidate(f.invocation, f.ordinaryExperience(), "Statement")
            for (input in listOf(candidate.copy(id = KnowledgeId("missing")),
                candidate.copy(statement = "Replaced"), candidate.copy(version = 9))) {
                shouldThrow<IllegalArgumentException> { service.validateKnowledge(f.invocation, input, f.actor) }
            }
            val attacker = SubjectRef.Person(PersonId("attacker"))
            val forged = candidate.copy(provenance = candidate.provenance.copy(author = attacker))
            shouldThrow<IllegalArgumentException> {
                service.deprecate(f.invocation.copy(actor = attacker), forged, "Forged author")
            }
            f.repos.knowledge.findById(candidate.id) shouldBe candidate
            f.count("knowledge") shouldBe 1L
        }
    }

    test("stale Knowledge cannot be restored after validation or deprecation and database reopen") {
        SignedActionFixture().use { f ->
            var service = f.ordinaryMemory()
            val candidate = service.proposeKnowledgeCandidate(f.invocation, f.ordinaryExperience(), "Statement")
            val validated = service.validateKnowledge(f.invocation, candidate, f.actor)
            f.reopen()
            service = f.ordinaryMemory()
            shouldThrow<IllegalArgumentException> { service.validateKnowledge(f.invocation, candidate, f.actor) }
            shouldThrow<IllegalArgumentException> { service.deprecate(f.invocation, candidate, "Stale") }
            val deprecated = service.deprecate(f.invocation, validated, "Superseded by new evidence")
            f.reopen()
            shouldThrow<IllegalArgumentException> {
                f.ordinaryMemory().validateKnowledge(f.invocation, validated, f.actor)
            }
            f.repos.knowledge.findById(candidate.id) shouldBe deprecated
            deprecated.provenance shouldBe candidate.provenance
            f.count("event") shouldBe 5L
            f.count("sync_journal") shouldBe 0L
        }
    }

    test("only current candidates validate and only candidates or validated Knowledge deprecate with a reason") {
        SignedActionFixture().use { f ->
            val service = f.ordinaryMemory()
            val candidate = service.proposeKnowledgeCandidate(f.invocation, f.ordinaryExperience(), "Statement")
            for (status in KnowledgeStatus.entries) {
                val current = candidate.copy(status = status)
                f.repos.knowledge.save(current)
                if (status != KnowledgeStatus.CANDIDATE) shouldThrow<IllegalArgumentException> {
                    service.validateKnowledge(f.invocation, current, f.actor)
                }
                if (status !in setOf(KnowledgeStatus.CANDIDATE, KnowledgeStatus.VALIDATED)) {
                    shouldThrow<IllegalArgumentException> { service.deprecate(f.invocation, current, "Reason") }
                }
                f.repos.knowledge.findById(candidate.id) shouldBe current
            }
            f.repos.knowledge.save(candidate)
            shouldThrow<IllegalArgumentException> { service.deprecate(f.invocation, candidate, " ") }
            service.deprecate(f.invocation, candidate, "Withdraw candidate").status shouldBe KnowledgeStatus.DEPRECATED
        }
    }

    test("all ordinary memory policies see persisted source context and denial leaves data unchanged") {
        SignedActionFixture().use { f ->
            val event = f.unsignedSource()
            val service = f.ordinaryMemory()
            val experience = service.createExperience(f.invocation, listOf(event), "Conclusion", "Conditions")
            val candidate = service.proposeKnowledgeCandidate(f.invocation, experience, "Statement")
            val checked = mutableListOf<String>()
            val denied = f.ordinaryMemory(rights = RightsConstraint {
                it.context shouldBe f.context
                checked.add(it.operation)
                throw RightsConstraintViolation("DENIED", "Fixture policy")
            })
            val invocation = f.invocation.copy(context = Context("bypass"))
            shouldThrow<RightsConstraintViolation> {
                denied.createExperience(invocation, listOf(event), "Denied", "Conditions")
            }
            shouldThrow<RightsConstraintViolation> {
                denied.proposeKnowledgeCandidate(invocation, experience, "Denied")
            }
            shouldThrow<RightsConstraintViolation> { denied.validateKnowledge(invocation, candidate, f.actor) }
            shouldThrow<RightsConstraintViolation> { denied.deprecate(invocation, candidate, "Denied") }
            checked shouldBe listOf("createExperience", "proposeKnowledgeCandidate", "validateKnowledge", "deprecate")
            f.count("experience") shouldBe 1L
            f.count("knowledge") shouldBe 1L
            f.repos.knowledge.findById(candidate.id) shouldBe candidate
        }
    }

    test("a failure after a Knowledge write rolls back proposal validation and deprecation") {
        for (operation in listOf("propose", "validate", "deprecate")) SignedActionFixture().use { f ->
            val experience = f.ordinaryExperience()
            val candidate = f.ordinaryMemory().proposeKnowledgeCandidate(f.invocation, experience, "Statement")
            val repository = f.repos.knowledge
            val failing = object : KnowledgeRepository by repository {
                override fun save(knowledge: Knowledge): Knowledge {
                    repository.save(knowledge)
                    error("Injected failure after write")
                }
            }
            val service = f.ordinaryMemory(knowledge = failing)
            shouldThrow<IllegalStateException> {
                when (operation) {
                    "propose" -> service.proposeKnowledgeCandidate(f.invocation, experience, "New statement")
                    "validate" -> service.validateKnowledge(f.invocation, candidate, f.actor)
                    else -> service.deprecate(f.invocation, candidate, "Reason")
                }
            }
            f.reopen()
            f.count("knowledge") shouldBe 1L
            f.repos.knowledge.findById(candidate.id) shouldBe candidate
        }
    }

    test("a failure after an Experience insert rolls back through the shared repository transaction") {
        SignedActionFixture().use { f ->
            val event = f.unsignedSource()
            val repository = f.repos.experiences
            val failing = object : ExperienceRepository by repository {
                override fun save(experience: Experience): Experience {
                    repository.save(experience)
                    error("Injected failure after write")
                }
            }
            shouldThrow<IllegalStateException> {
                f.ordinaryMemory(experiences = failing)
                    .createExperience(f.invocation, listOf(event), "Fail", "Conditions")
            }
            f.reopen()
            f.count("experience") shouldBe 0L
            f.count("event") shouldBe 1L
        }
    }
})

private fun SignedActionFixture.ordinaryMemory(rights: RightsConstraint = TestRightsConstraint,
    knowledge: KnowledgeRepository = repos.knowledge, experiences: ExperienceRepository = repos.experiences) =
    MemoryService(experiences, knowledge, Clock { now }, IdGenerator { UUID.randomUUID().toString() },
        rights, repos.events)

private fun SignedActionFixture.unsignedSource(): Event = repos.events.append(Event(EventId("source"),
    "OBSERVATION", actor, now, context, null, null, mapOf("observed" to "value"), null,
    Provenance(null, actor, now), "legacy-local-hash", null))

private fun SignedActionFixture.ordinaryExperience(): Experience =
    ordinaryMemory().createExperience(invocation, listOf(unsignedSource()), "Conclusion", "Conditions")
