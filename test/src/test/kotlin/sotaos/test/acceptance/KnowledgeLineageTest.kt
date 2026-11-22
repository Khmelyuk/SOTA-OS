package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.memory.*
import sotaos.domain.shared.*

class KnowledgeLineageTest : FunSpec({
    test("persisted Knowledge content version provenance and predecessor cannot be rewritten") {
        SignedActionFixture().use { f ->
            val original = f.lineageRoot()
            val changes = listOf(original.copy(statement = "Rewritten"), original.copy(version = 2),
                original.copy(supersedes = KnowledgeId("other")), original.copy(context = Context("other")),
                original.copy(evidence = listOf(EvidenceId("new"))),
                original.copy(derivedFromExperience = listOf(ExperienceId("new-source"))),
                original.copy(provenance = original.provenance.copy(recordedAt = f.now.plusSeconds(1))))
            changes.forEach { value -> shouldThrow<IllegalArgumentException> { f.repos.knowledge.save(value) } }
            f.reopen()
            f.repos.knowledge.findById(original.id) shouldBe original
            val validated = original.copy(status = KnowledgeStatus.VALIDATED)
            f.repos.knowledge.save(validated) shouldBe validated
        }
    }

    test("a local predecessor cannot acquire two successor versions after reopen") {
        SignedActionFixture().use { f ->
            val root = f.lineageRoot()
            val child = root.copy(id = KnowledgeId("child"), version = 2, supersedes = root.id)
            f.repos.knowledge.save(child)
            f.reopen()
            shouldThrow<IllegalArgumentException> { f.repos.knowledge.save(child.copy(id = KnowledgeId("second"))) }
            f.count("knowledge") shouldBe 2L
            f.repos.knowledge.findById(root.id) shouldBe root
        }
    }

    test("new Knowledge rejects invalid numbering missing parents cycles and context or author changes") {
        SignedActionFixture().use { f ->
            val root = f.lineageRoot()
            val child = root.copy(id = KnowledgeId("child"), version = 2, supersedes = root.id)
            val invalid = listOf(child.copy(supersedes = KnowledgeId("missing")), child.copy(version = 3),
                child.copy(supersedes = child.id), child.copy(context = Context("other")),
                child.copy(provenance = root.provenance.copy(author = SubjectRef.Person(PersonId("other")))),
                child.copy(version = 0, supersedes = null))
            invalid.forEach { value -> shouldThrow<IllegalArgumentException> { f.repos.knowledge.save(value) } }
            f.count("knowledge") shouldBe 1L
        }
    }

    test("a trusted legacy positive-version root can retain its known successor without invented ancestors") {
        SignedActionFixture().use { f ->
            val root = f.lineageRoot(version = 3)
            val child = root.copy(id = KnowledgeId("child"), version = 4, supersedes = root.id)
            f.repos.knowledge.save(child)
            f.reopen()
            f.repos.knowledge.findById(root.id) shouldBe root
            f.repos.knowledge.findById(child.id) shouldBe child
        }
    }
})

private fun SignedActionFixture.lineageRoot(version: Int = 1): Knowledge = repos.knowledge.save(
    Knowledge(KnowledgeId("root"), "Original", emptyList(), emptyList(), context, KnowledgeStatus.CANDIDATE,
        version, null, Provenance(null, actor, now)))
