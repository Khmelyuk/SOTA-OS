package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.agency.Outcome
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightVerifiedRecordRepository
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.security.Ed25519EventSigner
import java.io.IOException

class SignedResultTest : FunSpec({
    test("signed Result binds persisted Action and converges after lost reply and reopen without remote execution") {
        SignedActionFixture().use { source -> SignedActionFixture().use { destination ->
            source.key()
            destination.key(source.signer)
            source.peer("destination")
            val token = destination.peer("source")
            var sender = source.runtime()
            var receiver = destination.runtime("destination")
            val action = sender.execute(source.invocation, source.decision(sender))
            val parent = source.journal.records().single()
            val result = sender.recordResult(source.invocation, action, Outcome.PARTIAL, "Partial delivery")
            val record = source.journal.records().last()
            record.event.type shouldBe "RESULT_RECORDED"
            record.event.resultRef shouldBe result.id.value
            record.event.payload shouldBe mapOf("actionId" to action.id.value,
                "outcome" to "PARTIAL", "description" to "Partial delivery")
            record.event.context shouldBe action.context
            record.event.authorityRef shouldBe action.authority.value
            record.event.decisionRef shouldBe action.decision?.value
            record.event.timestamp shouldBe result.timestamp
            record.event.provenance shouldBe Provenance(parent.event.id, action.actor, result.timestamp)
            record.parents shouldBe setOf(parent.event.id)
            record.assertion shouldBe null
            sender.sync.integrity.check(record)
            shouldThrow<IllegalArgumentException> { sender.sync.integrity.check(record.copy(parents = emptySet())) }
            source.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
            SqlDelightVerifiedRecordRepository(source.store.database).find(record.event.id)?.key?.actor shouldBe
                source.actor
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
            repeat(2) { sender.sync.synchronize(SotaId("destination"), transport) shouldBe PeerCheckpoint(2, 2) }
            destination.journal.records() shouldBe listOf(parent, record)
            source.count("result") shouldBe 1L
            destination.count("action") shouldBe 0L
            destination.count("result") shouldBe 0L
        } }
    }
    test("result event journal and receipt insertion failures roll back the whole Result transaction") {
        listOf("result", "event", "sync_journal", "verified_sync_record").forEach { table ->
            SignedActionFixture().use { f ->
                f.key()
                val runtime = f.runtime()
                val action = runtime.execute(f.invocation, f.decision(runtime))
                val parent = f.journal.records().single()
                f.driver.execute(null, "CREATE TRIGGER injected_failure BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected result failure'); END", 0)
                shouldThrow<Exception> { runtime.recordResult(f.invocation, action, Outcome.SUCCESS, "Done") }
                f.count("result") shouldBe 0L
                listOf("action", "event", "sync_journal", "verified_sync_record").forEach { f.count(it) shouldBe 1L }
                f.journal.records() shouldBe listOf(parent)
                f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
                f.reopen()
                f.runtime().recordResult(f.invocation, action, Outcome.SUCCESS, "Done")
                f.count("result") shouldBe 1L
                f.journal.records().size shouldBe 2
            }
        }
    }
    test("forged unknown wrong-author and wrong-origin Actions cannot produce signed Results") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val action = runtime.execute(f.invocation, f.decision(runtime))
            listOf(action.copy(id = ActionId("unknown")), action.copy(target = "forged"),
                action.copy(context = Context("forged"))).forEach { invalid ->
                shouldThrow<IllegalArgumentException> {
                    runtime.recordResult(f.invocation, invalid, Outcome.SUCCESS, "Forged")
                }
            }
            shouldThrow<IllegalArgumentException> {
                runtime.recordResult(f.invocation.copy(actor = SubjectRef.Person(PersonId("other"))),
                    action, Outcome.SUCCESS, "Wrong author")
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime("other-origin").recordResult(f.invocation, action, Outcome.SUCCESS, "Wrong origin")
            }
            f.count("result") shouldBe 0L
            f.journal.records().size shouldBe 1
        }
    }
    test("new Result requires current actor key while its Action retains old-key verification evidence") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val action = runtime.execute(f.invocation, f.decision(runtime))
            f.repos.actorSigningKeys.revoke(f.actor, "action-key", f.now)
            shouldThrow<IllegalArgumentException> {
                runtime.recordResult(f.invocation, action, Outcome.FAILURE, "Fail")
            }
            val nextKey = Ed25519EventSigner.generate()
            f.key(nextKey)
            shouldThrow<IllegalArgumentException> {
                runtime.recordResult(f.invocation, action, Outcome.FAILURE, "Fail")
            }
            f.count("result") shouldBe 0L
            f.reopen()
            f.runtime(signingKey = nextKey).recordResult(f.invocation, action, Outcome.FAILURE, "Fail")
            val result = f.journal.records().last()
            nextKey.verify(result.event.contentHash, requireNotNull(result.event.signature)) shouldBe true
            result.parents shouldBe setOf(f.journal.records().first().event.id)
        }
    }
    test("unsigned Action history cannot be promoted into signed Result history") {
        SignedActionFixture().use { f ->
            f.key()
            val repositories = f.repos
            val legacy = sotaos.application.services.MissionActionService(repositories.missions,
                repositories.decisions, repositories.actions, repositories.results, repositories.authorities,
                repositories.events, sotaos.application.services.AuthorityLineage(repositories.authorities)::isValid,
                sotaos.security.RightsConstraintDecorator(listOf(sotaos.security.NoAgentActionRule)),
                Clock { f.now }, IdGenerator { java.util.UUID.randomUUID().toString() })
            val decision = f.decision(f.runtime())
            val action = legacy.execute(f.invocation, decision)
            shouldThrow<IllegalArgumentException> {
                f.runtime().recordResult(f.invocation, action, Outcome.SUCCESS, "Cannot upgrade history")
            }
            shouldThrow<IllegalArgumentException> {
                legacy.recordResult(f.invocation, action.copy(target = "forged"), Outcome.SUCCESS, "Forged")
            }
            f.count("result") shouldBe 0L
            f.journal.records().size shouldBe 0
            repositories.events.findByActor(f.actor).single().signature shouldBe null
        }
    }
    test("Result rights check uses persisted Action context and denial leaves no writes") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val action = runtime.execute(f.invocation, f.decision(runtime))
            val denied = f.runtime(rights = RightsConstraint { request ->
                request.operation shouldBe "recordResult"
                request.context shouldBe action.context
                throw RightsConstraintViolation("DENIED", "Fixture policy denies recording")
            })
            shouldThrow<RightsConstraintViolation> {
                denied.recordResult(f.invocation.copy(context = Context("bypass")), action, Outcome.SUCCESS, "Done")
            }
            f.count("result") shouldBe 0L
            f.journal.records().size shouldBe 1
        }
    }
})
