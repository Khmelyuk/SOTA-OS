package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.MemoryService
import sotaos.domain.memory.*
import sotaos.domain.shared.*
import sotaos.protocol.JsonMemorySnapshotCodec
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

class KnowledgeRevisionTest : FunSpec({
    test("new candidates retain reviewed predecessors and exact revision evidence across restart") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            val runtime = f.revisionRuntime(signed)
            val source = f.revisionSource(runtime)
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, source, "Initial conclusion")
            val approved = runtime.validateKnowledge(f.invocation, candidate, f.actor)
            val reason = "New interpretation\nRetain the original evidence."
            val revised = runtime.reviseKnowledge(f.invocation, approved, source, "Revised conclusion", reason)
            revised.status shouldBe KnowledgeStatus.CANDIDATE
            revised.version shouldBe 2
            revised.supersedes shouldBe approved.id
            revised.derivedFromExperience shouldBe listOf(source.id)
            revised.evidence shouldBe emptyList()
            revised.provenance shouldBe Provenance(source.provenance.sourceEventId, f.actor, f.now,
                listOf(approved.id.value, source.id.value))
            f.repos.knowledge.findById(approved.id) shouldBe approved
            f.assertRevisionAudit(signed, approved, revised, source, reason)
            f.reopen()
            f.repos.knowledge.findById(revised.id) shouldBe revised
            val reopened = f.revisionRuntime(signed)
            val reviewed = reopened.validateKnowledge(f.invocation, revised, f.actor)
            val third = reopened.reviseKnowledge(f.invocation, reviewed, source, "Third conclusion", "Further review")
            third.version shouldBe 3
            third.supersedes shouldBe revised.id
            f.repos.knowledge.findById(approved.id) shouldBe approved
            f.repos.knowledge.findById(revised.id) shouldBe reviewed
            f.repos.knowledge.findSuccessors(approved.id) shouldBe listOf(reviewed)
            f.count("knowledge") shouldBe 3L
        }
    }

    test("revision rejects changed sources actors contexts and empty or unchanged content without writes") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            val runtime = f.revisionRuntime(signed)
            val source = f.revisionSource(runtime)
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, source, "Initial")
            val otherContext = f.repos.experiences.save(source.copy(id = ExperienceId("other-context"),
                context = Context("other")))
            val otherAuthor = f.repos.experiences.save(source.copy(id = ExperienceId("other-author"),
                provenance = source.provenance.copy(author = SubjectRef.Person(PersonId("other")))))
            val future = f.repos.experiences.save(source.copy(id = ExperienceId("future"),
                provenance = source.provenance.copy(recordedAt = f.now.plusSeconds(1))))
            val before = f.repos.events.findByActor(f.actor)
            val attempts = listOf<() -> Knowledge>(
                { runtime.reviseKnowledge(f.invocation, candidate, source, "Initial", "Unchanged") },
                { runtime.reviseKnowledge(f.invocation, candidate, source, " ", "Blank") },
                { runtime.reviseKnowledge(f.invocation, candidate, source, "New", " \n") },
                { runtime.reviseKnowledge(f.invocation, candidate.copy(statement = "Forged"), source, "New", "Why") },
                { runtime.reviseKnowledge(f.invocation, candidate, source.copy(conclusion = "Forged"), "New", "Why") },
                { runtime.reviseKnowledge(f.invocation, candidate, otherContext, "New", "Why") },
                { runtime.reviseKnowledge(f.invocation, candidate, otherAuthor, "New", "Why") },
                { runtime.reviseKnowledge(f.invocation, candidate, future, "New", "Why") },
                { runtime.reviseKnowledge(f.invocation.copy(actor = SubjectRef.Person(PersonId("other"))),
                    candidate, source, "New", "Why") })
            attempts.forEach { shouldThrow<IllegalArgumentException> { it() } }
            f.repos.events.findByActor(f.actor) shouldBe before
            f.repos.knowledge.findById(candidate.id) shouldBe candidate
            f.count("knowledge") shouldBe 1L
        }
    }

    test("stale predecessor fork and ID reuse cannot replace existing versions or append audit") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            val runtime = f.revisionRuntime(signed)
            val source = f.revisionSource(runtime)
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, source, "Initial")
            val approved = runtime.validateKnowledge(f.invocation, candidate, f.actor)
            shouldThrow<IllegalArgumentException> {
                runtime.reviseKnowledge(f.invocation, candidate, source, "Stale", "Why")
            }
            shouldThrow<IllegalArgumentException> {
                f.revisionRuntime(signed, ids = IdGenerator { approved.id.value })
                    .reviseKnowledge(f.invocation, approved, source, "Collision", "Why")
            }
            val revised = runtime.reviseKnowledge(f.invocation, approved, source, "New", "Why")
            val before = f.repos.events.findByActor(f.actor)
            f.reopen()
            shouldThrow<IllegalArgumentException> {
                f.revisionRuntime(signed).reviseKnowledge(f.invocation, approved, source, "Fork", "Why")
            }
            f.repos.events.findByActor(f.actor) shouldBe before
            f.repos.knowledge.findById(approved.id) shouldBe approved
            f.repos.knowledge.findById(revised.id) shouldBe revised
            f.count("knowledge") shouldBe 2L
        }
    }

    test("revision rights receive persisted context and denial leaves no memory or audit writes") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            val runtime = f.revisionRuntime(signed)
            val source = f.revisionSource(runtime)
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, source, "Initial")
            val before = f.repos.events.findByActor(f.actor)
            val denied = f.revisionRuntime(signed, rights = RightsConstraint { request ->
                request.context shouldBe f.context
                request.operation shouldBe "reviseKnowledge"
                throw RightsConstraintViolation("DENIED", "Fixture policy")
            })
            shouldThrow<RightsConstraintViolation> {
                denied.reviseKnowledge(f.invocation.copy(context = Context("bypass")), candidate, source, "New", "Why")
            }
            f.repos.events.findByActor(f.actor) shouldBe before
            f.count("knowledge") shouldBe 1L
        }
    }

    test("revision entity event journal and receipt failures roll back and permit retry after restart") {
        for (signed in listOf(false, true)) {
            val tables = if (signed) listOf("knowledge", "event", "sync_journal", "verified_sync_record")
                else listOf("knowledge", "event")
            for (table in tables) SignedActionFixture().use { f ->
                val runtime = f.revisionRuntime(signed)
                val source = f.revisionSource(runtime)
                val candidate = runtime.proposeKnowledgeCandidate(f.invocation, source, "Initial")
                val before = f.repos.events.findByActor(f.actor)
                val journal = f.journal.records()
                val receipts = f.count("verified_sync_record")
                f.driver.execute(null, "CREATE TRIGGER revision_failure BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected revision failure'); END", 0)
                shouldThrow<Exception> { runtime.reviseKnowledge(f.invocation, candidate, source, "New", "Why") }
                f.reopen()
                f.repos.events.findByActor(f.actor) shouldBe before
                f.journal.records() shouldBe journal
                f.count("verified_sync_record") shouldBe receipts
                f.count("knowledge") shouldBe 1L
                f.repos.knowledge.findById(candidate.id) shouldBe candidate
                f.driver.execute(null, "DROP TRIGGER revision_failure", 0)
                f.revisionRuntime(signed).reviseKnowledge(f.invocation, candidate, source, "Retried", "Why")
                f.count("knowledge") shouldBe 2L
            }
        }
    }
})

internal fun SignedActionFixture.revisionRuntime(signed: Boolean,
    rights: RightsConstraint = TestRightsConstraint,
    ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() }): MemoryKnowledgeProtocol =
    if (signed) memory(rights = rights, ids = ids)
    else MemoryService(repos.experiences, repos.knowledge, Clock { now }, ids, rights, repos.events)

internal fun SignedActionFixture.revisionSource(runtime: MemoryKnowledgeProtocol): Experience {
    key()
    return runtime.createExperience(invocation, sourceEvents(), "Observed conclusion", "Conditions")
}

private fun SignedActionFixture.assertRevisionAudit(signed: Boolean, previous: Knowledge, revised: Knowledge,
    source: Experience, reason: String) {
    val type = if (signed) "KNOWLEDGE_REVISED" else "LOCAL_KNOWLEDGE_REVISED"
    val event = repos.events.findByActor(actor).single { it.type == type }
    event.payload["reason"] shouldBe reason
    event.payload["purpose"] shouldBe invocation.purpose
    event.payload["entityId"] shouldBe revised.id.value
    if (signed) {
        val records = journal.records()
        val revision = records.single { it.event.id == event.id }
        revision.parents shouldBe records
            .filter { it.event.type in setOf("KNOWLEDGE_VALIDATED", "EXPERIENCE_RECORDED") }
            .map { it.event.id }.toSet()
        event.payload["snapshot"] shouldBe JsonMemorySnapshotCodec().encode(revised)
        memory().sync.integrity.check(revision)
        signer.verify(event.contentHash, requireNotNull(event.signature)) shouldBe true
        shouldThrow<IllegalArgumentException> { memory().sync.integrity.check(revision.copy(parents = emptySet())) }
    } else {
        (event.payload["before"] as Map<*, *>)["id"] shouldBe previous.id.value
        (event.payload["after"] as Map<*, *>)["supersedes"] shouldBe previous.id.value
        (event.payload["source"] as Map<*, *>)["id"] shouldBe source.id.value
        event.signature shouldBe null
    }
}
