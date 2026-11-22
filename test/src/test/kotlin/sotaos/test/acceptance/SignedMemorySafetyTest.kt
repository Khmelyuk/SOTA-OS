package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.MemoryService
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.security.Ed25519EventSigner
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

class SignedMemorySafetyTest : FunSpec({
    test("empty duplicate forged unsigned and unverified Events cannot be promoted into signed Experience") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val events = f.sourceEvents()
            val source = events.first()
            val unsigned = source.copy(id = EventId("unsigned"), signature = null)
            f.repos.events.append(unsigned)
            val unverified = runtime.sync.integrity.sign(SyncRecord(source.copy(id = EventId("unverified")),
                SotaId("source")), f.signer)
            f.journal.append(unverified)
            val invalid = listOf(emptyList(), listOf(source, source), listOf(unsigned), listOf(unverified.event),
                listOf(source.copy(id = EventId("unknown"))), listOf(source.copy(payload = mapOf("forged" to true))))
            val before = f.journal.records()
            invalid.forEach { input -> shouldThrow<IllegalArgumentException> {
                runtime.createExperience(f.invocation, input, "Denied", "Conditions")
            } }
            f.count("experience") shouldBe 0L
            f.journal.records() shouldBe before
        }
    }

    test("mixed source contexts wrong authors foreign origin and Agents are rejected before memory writes") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val events = f.sourceEvents()
            val other = runtime.sync.integrity.sign(SyncRecord(events.first().copy(id = EventId("other-context"),
                context = Context("other")), SotaId("source")), f.signer)
            runtime.sync.recordLocal(other)
            shouldThrow<IllegalArgumentException> {
                runtime.createExperience(f.invocation, listOf(events.first(), other.event), "Denied", "Conditions")
            }
            shouldThrow<IllegalArgumentException> {
                f.memory(node = "foreign").createExperience(f.invocation, events, "Denied", "Conditions")
            }
            for (actor in listOf(SubjectRef.Person(PersonId("other")), SubjectRef.Agent(AgentId("agent")))) {
                shouldThrow<IllegalArgumentException> {
                    f.memory(actors = setOf(f.actor, actor), rights = TestRightsConstraint)
                        .createExperience(f.invocation.copy(actor = actor), events, "Denied", "Conditions")
                }
            }
            f.count("experience") shouldBe 0L
        }
    }

    test("forged or legacy Experience and stale or forged Knowledge cannot create signed lifecycle facts") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val experience = f.experience(runtime)
            val legacy = MemoryService(f.repos.experiences, f.repos.knowledge, Clock { f.now },
                IdGenerator { UUID.randomUUID().toString() }, TestRightsConstraint, f.repos.events)
            val unsigned = legacy.createExperience(f.invocation,
                listOf(f.journal.records().first().event), "Unsigned", "Conditions")
            listOf(experience.copy(conclusion = "Forged"), experience.copy(id = ExperienceId("unknown")), unsigned)
                .forEach { value -> shouldThrow<IllegalArgumentException> {
                    runtime.proposeKnowledgeCandidate(f.invocation, value, "Denied")
                } }
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, experience, "Statement")
            shouldThrow<IllegalArgumentException> {
                runtime.validateKnowledge(f.invocation, candidate.copy(statement = "Forged"), f.actor)
            }
            val validated = runtime.validateKnowledge(f.invocation, candidate, f.actor)
            shouldThrow<IllegalArgumentException> { runtime.validateKnowledge(f.invocation, candidate, f.actor) }
            shouldThrow<IllegalArgumentException> { runtime.deprecate(f.invocation, candidate, "Stale") }
            shouldThrow<IllegalArgumentException> { runtime.deprecate(f.invocation, validated, " ") }
            shouldThrow<IllegalArgumentException> {
                f.memory(ids = IdGenerator { candidate.id.value })
                    .proposeKnowledgeCandidate(f.invocation, experience, "Overwrite")
            }
            f.repos.knowledge.findById(candidate.id) shouldBe validated
            f.count("knowledge") shouldBe 1L
            f.journal.records().size shouldBe 5
        }
    }

    test("rights denial uses persisted context and does not persist a memory state change") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, f.experience(runtime), "Statement")
            val denied = f.memory(rights = RightsConstraint { request ->
                request.context shouldBe f.context
                throw RightsConstraintViolation("DENIED", "Fixture policy")
            })
            shouldThrow<RightsConstraintViolation> {
                denied.validateKnowledge(f.invocation.copy(context = Context("bypass")), candidate, f.actor)
            }
            f.repos.knowledge.findById(candidate.id) shouldBe candidate
            f.journal.records().size shouldBe 4
        }
    }

    test("Agents cannot validate and a replaced persisted snapshot cannot acquire a signed history") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, f.experience(runtime), "Statement")
            val agent = SubjectRef.Agent(AgentId("agent"))
            shouldThrow<IllegalArgumentException> {
                f.memory(actors = setOf(f.actor, agent), rights = TestRightsConstraint)
                    .validateKnowledge(f.invocation.copy(actor = agent), candidate, agent)
            }
            val replacement = candidate.copy(statement = "Replaced outside signed runtime")
            // Bypass the immutable repository to simulate out-of-band database corruption.
            f.driver.execute(null, "UPDATE knowledge SET statement = ? WHERE knowledge_id = ?", 2) {
                bindString(0, replacement.statement)
                bindString(1, replacement.id.value)
            }
            shouldThrow<IllegalArgumentException> {
                runtime.validateKnowledge(f.invocation, replacement, f.actor)
            }
            f.repos.knowledge.findById(candidate.id) shouldBe replacement
            f.journal.records().size shouldBe 4
        }
    }

    test("current keys are mandatory for new P07 facts while exact historical source proofs survive rotation") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val events = f.sourceEvents()
            f.repos.actorSigningKeys.revoke(f.actor, "action-key", f.now)
            shouldThrow<IllegalArgumentException> {
                runtime.createExperience(f.invocation, events, "Denied", "Conditions")
            }
            val next = Ed25519EventSigner.generate()
            f.key(next)
            shouldThrow<IllegalArgumentException> {
                runtime.createExperience(f.invocation, events, "Wrong key", "Conditions")
            }
            f.count("experience") shouldBe 0L
            f.reopen()
            val rotated = f.memory(signers = { next })
            val experience = rotated.createExperience(f.invocation, events, "Rotated", "Conditions")
            val record = f.journal.records().last()
            next.verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
            rotated.proposeKnowledgeCandidate(f.invocation, experience, "Statement").provenance shouldBe
                experience.provenance
        }
    }
})
