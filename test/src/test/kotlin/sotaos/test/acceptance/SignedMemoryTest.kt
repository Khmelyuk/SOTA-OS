package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.ActorSigningKeyRecord
import sotaos.application.sync.*
import sotaos.domain.memory.KnowledgeStatus
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightVerifiedRecordRepository
import sotaos.protocol.JsonMemorySnapshotCodec
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.security.Ed25519EventSigner
import java.io.IOException

class SignedMemoryTest : FunSpec({
    test("P07 preserves complete snapshots and signs every source link across database reopen") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.memory()
            val source = f.sourceEvents()
            val experience = runtime.createExperience(f.invocation, source, "Conclusion", "Conditions")
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, experience, "Proposition")
            val validated = runtime.validateKnowledge(f.invocation, candidate, f.actor)
            val deprecated = runtime.deprecate(f.invocation, validated, "New contradictory evidence")
            val records = f.journal.records()
            f.count("event") shouldBe records.size.toLong()
            val facts = records.drop(source.size)
            facts.map { it.event.type } shouldBe listOf("EXPERIENCE_RECORDED", "KNOWLEDGE_PROPOSED",
                "KNOWLEDGE_VALIDATED", "KNOWLEDGE_DEPRECATED")
            facts.map { it.event.payload["snapshot"] } shouldBe listOf(
                JsonMemorySnapshotCodec().encode(experience), JsonMemorySnapshotCodec().encode(candidate),
                JsonMemorySnapshotCodec().encode(validated), JsonMemorySnapshotCodec().encode(deprecated))
            facts.first().parents shouldBe source.map { it.id }.toSet()
            facts.zipWithNext().forEach { (previous, next) -> next.parents shouldBe setOf(previous.event.id) }
            facts.forEach { record ->
                runtime.sync.integrity.check(record)
                f.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
                record.assertion shouldBe null
                record.event.provenance.author shouldBe f.actor
                (record.event.provenance.sourceEventId in record.parents) shouldBe true
                SqlDelightVerifiedRecordRepository(f.store.database).find(record.event.id)?.key?.actor shouldBe f.actor
                shouldThrow<IllegalArgumentException> {
                    runtime.sync.integrity.check(record.copy(parents = emptySet()))
                }
            }
            facts.last().event.payload["reason"] shouldBe "New contradictory evidence"
            f.reopen()
            f.repos.experiences.findById(experience.id) shouldBe experience
            f.repos.knowledge.findById(candidate.id) shouldBe deprecated
            f.journal.records() shouldBe records
        }
    }

    test("P07 recovers lost acknowledgements after reopen without creating remote memory entities") {
        SignedActionFixture().use { a -> SignedActionFixture().use { b ->
            a.key(); b.key(a.signer)
            a.peer("destination")
            val token = b.peer("source")
            var sender = a.memory()
            var receiver = b.memory("destination")
            val candidate = sender.proposeKnowledgeCandidate(a.invocation, a.experience(sender), "Statement")
            sender.validateKnowledge(a.invocation, candidate, a.actor).status shouldBe KnowledgeStatus.VALIDATED
            val records = a.journal.records()
            val codec = JsonSyncMessageCodec()
            var loseAck = true
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    val response = receiver.sync.exchange("Bearer $token", codec.encodeRequest(request))
                    if (loseAck) throw IOException("lost acknowledgement")
                    return codec.decodeResponse(response)
                }
            }
            shouldThrow<IOException> { sender.sync.synchronize(SotaId("destination"), transport) }
            a.reopen(); b.reopen()
            sender = a.memory(); receiver = b.memory("destination")
            loseAck = false
            repeat(2) { sender.sync.synchronize(SotaId("destination"), transport) shouldBe PeerCheckpoint(5, 5) }
            b.journal.records() shouldBe records
            listOf("experience", "knowledge", "action", "result").forEach { b.count(it) shouldBe 0L }
        } }
    }

    test("human collective validation records the validator while preserving the original Knowledge author") {
        SignedActionFixture().use { f ->
            f.key()
            val validator = SubjectRef.Core(CoreId("core"))
            val key = Ed25519EventSigner.generate()
            f.repos.actorSigningKeys.save(ActorSigningKeyRecord(validator, "validator", key.publicKeyEncoded()), f.now)
            val runtime = f.memory(actors = setOf(f.actor, validator),
                signers = { if (it == validator) key else f.signer })
            val candidate = runtime.proposeKnowledgeCandidate(f.invocation, f.experience(runtime), "Statement")
            val validated = runtime.validateKnowledge(f.invocation.copy(actor = validator), candidate, validator)
            validated.provenance shouldBe candidate.provenance
            f.journal.records().last().event.actor shouldBe validator
            f.journal.records().last().event.provenance.author shouldBe validator
            runtime.deprecate(f.invocation, validated, "Author withdrawal").status shouldBe KnowledgeStatus.DEPRECATED
        }
    }
})
