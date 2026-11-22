package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.memory.*
import sotaos.domain.shared.*

class KnowledgeRevisionBoundaryTest : FunSpec({
    test("only candidate validated and deprecated predecessors enter the bounded revision lifecycle") {
        for (status in KnowledgeStatus.entries) SignedActionFixture().use { f ->
            val runtime = f.revisionRuntime(false)
            val source = f.revisionSource(runtime)
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, source, "Initial")
            val previous = f.repos.knowledge.save(candidate.copy(status = status))
            val before = f.repos.events.findByActor(f.actor)
            if (status in setOf(KnowledgeStatus.CANDIDATE, KnowledgeStatus.VALIDATED, KnowledgeStatus.DEPRECATED)) {
                runtime.reviseKnowledge(f.invocation, previous, source, "New", "Why").status shouldBe
                    KnowledgeStatus.CANDIDATE
            } else {
                shouldThrow<IllegalArgumentException> {
                    runtime.reviseKnowledge(f.invocation, previous, source, "New", "Why")
                }
                f.repos.events.findByActor(f.actor) shouldBe before
                f.count("knowledge") shouldBe 1L
            }
            f.repos.knowledge.findById(previous.id) shouldBe previous
        }
    }

    test("corrupt recorded ancestors invalid version numbers and overflow cannot gain a successor") {
        for (mutation in listOf("supersedes = 'missing'", "supersedes = 'root'", "version = 0",
            "version = 2147483647")) SignedActionFixture().use { f ->
            val runtime = f.revisionRuntime(false)
            val source = f.revisionSource(runtime)
            val root = Knowledge(KnowledgeId("root"), "Root", listOf(source.id), emptyList(), f.context,
                KnowledgeStatus.CANDIDATE, 1, null, source.provenance)
            f.repos.knowledge.save(root)
            val previous = if (mutation == "version = 2147483647") root else
                f.repos.knowledge.save(root.copy(id = KnowledgeId("child"), version = 2, supersedes = root.id))
            val before = f.repos.events.findByActor(f.actor)
            // A legacy or externally corrupted row must fail revision rather than gain invented history.
            f.driver.execute(null, "UPDATE knowledge SET $mutation WHERE knowledge_id = 'root'", 0)
            f.reopen()
            val corrupt = requireNotNull(f.repos.knowledge.findById(previous.id))
            shouldThrow<IllegalArgumentException> {
                f.revisionRuntime(false).reviseKnowledge(f.invocation, corrupt, source, "New", "Why")
            }
            f.count("knowledge") shouldBe if (previous == root) 1L else 2L
            f.repos.events.findByActor(f.actor) shouldBe before
        }
    }

    test("signed revision requires signed proof for both predecessor and Experience") {
        SignedActionFixture().use { f ->
            val signed = f.revisionRuntime(true)
            val source = f.revisionSource(signed)
            val candidate = signed.proposeKnowledgeCandidate(f.invocation, source, "Signed original")
            val local = f.revisionRuntime(false)
            val legacy = local.proposeKnowledgeCandidate(f.invocation, source, "Local original")
            val unsigned = f.repos.experiences.save(source.copy(id = ExperienceId("legacy-source")))
            val before = f.journal.records()
            shouldThrow<IllegalArgumentException> {
                signed.reviseKnowledge(f.invocation, legacy, source, "New", "Why")
            }
            shouldThrow<IllegalArgumentException> {
                signed.reviseKnowledge(f.invocation, candidate, unsigned, "New", "Why")
            }
            f.journal.records() shouldBe before
            f.count("knowledge") shouldBe 2L
        }
    }
})
