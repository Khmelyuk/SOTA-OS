package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.services.ActionNotAuthorizedException
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightVerifiedRecordRepository
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.security.Ed25519EventSigner
import java.io.IOException

class SignedActionTest : FunSpec({
    test("authorized action is signed and converges after lost acknowledgement and reopen without remote execution") {
        SignedActionFixture().use { source -> SignedActionFixture().use { destination ->
            source.key()
            destination.key(source.signer)
            source.peer("destination")
            val token = destination.peer("source")
            var sender = source.runtime()
            var receiver = destination.runtime("destination")
            val decision = source.decision(sender)
            val action = sender.execute(source.invocation, decision)
            val record = source.journal.records().single()
            record.event.type shouldBe "ACTION_EXECUTED"
            record.event.payload["actionId"] shouldBe action.id.value
            record.event.authorityRef shouldBe decision.authorityRef.value
            record.event.decisionRef shouldBe decision.id.value
            record.event.provenance.author shouldBe source.actor
            record.parents shouldBe emptySet()
            record.assertion shouldBe null
            source.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
            sender.sync.integrity.check(record)
            val receipt = SqlDelightVerifiedRecordRepository(source.store.database).find(record.event.id)
            receipt?.key?.actor shouldBe source.actor
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
            source.reopen()
            destination.reopen()
            sender = source.runtime()
            receiver = destination.runtime("destination")
            loseAck = false
            repeat(2) { sender.sync.synchronize(SotaId("destination"), transport) shouldBe PeerCheckpoint(1, 1) }
            destination.journal.records() shouldBe listOf(record)
            source.count("action") shouldBe 1L
            destination.count("action") shouldBe 0L
        } }
    }
    test("journal and verification receipt failures roll back action event journal and receipt") {
        listOf("sync_journal", "verified_sync_record").forEach { table ->
            SignedActionFixture().use { f ->
                f.key()
                val runtime = f.runtime()
                val decision = f.decision(runtime)
                f.driver.execute(null, "CREATE TRIGGER injected_failure BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected failure'); END", 0)
                shouldThrow<Exception> { runtime.execute(f.invocation, decision) }
                listOf("action", "event", "sync_journal", "verified_sync_record").forEach {
                    f.count(it) shouldBe 0L
                }
                f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
                f.reopen()
                f.runtime().execute(f.invocation, decision)
                f.count("action") shouldBe 1L
                f.journal.records().size shouldBe 1
            }
        }
    }
    test("missing mismatched revoked and nonlocal keys cannot commit an action") {
        SignedActionFixture().use { f ->
            val runtime = f.runtime()
            val decision = f.decision(runtime)
            shouldThrow<IllegalArgumentException> { runtime.execute(f.invocation, decision) }
            f.key(Ed25519EventSigner.generate())
            shouldThrow<IllegalArgumentException> { runtime.execute(f.invocation, decision) }
            f.key()
            shouldThrow<IllegalArgumentException> {
                f.runtime(actors = setOf(SubjectRef.Person(PersonId("other")))).execute(f.invocation, decision)
            }
            f.repos.actorSigningKeys.revoke(f.actor, "action-key", f.now)
            shouldThrow<IllegalArgumentException> { runtime.execute(f.invocation, decision) }
            f.count("action") shouldBe 0L
            f.count("event") shouldBe 0L
            f.journal.records().size shouldBe 0
        }
    }
    test("signing does not bypass consent persisted decisions or revoked authority") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val decision = f.decision(runtime, listOf(PersonId("affected")))
            shouldThrow<ActionNotAuthorizedException> { runtime.execute(f.invocation, decision) }
            shouldThrow<ActionNotAuthorizedException> {
                runtime.execute(f.invocation, decision.copy(affectedPersons = emptyList()))
            }
            val ordinary = f.decision(runtime)
            val authority = requireNotNull(f.repos.authorities.findById(f.authorityId))
            f.repos.authorities.save(authority.copy(state = LifecycleState.REVOKED))
            shouldThrow<ActionNotAuthorizedException> { runtime.execute(f.invocation, ordinary) }
            f.count("action") shouldBe 0L
            f.journal.records().size shouldBe 0
        }
    }
})
