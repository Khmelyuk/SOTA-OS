package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncConcurrencyTest : FunSpec({
    test("inbound exchange proceeds during outgoing network wait and stale reply cannot overwrite its cursor") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            val tokenForA = a.enroll(b)
            b.enroll(a)
            a.record("local")
            val remote = b.record("remote")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return SyncResponse(request.id, b.id, request.batch.through, SyncBatch(0, 1, listOf(remote)))
                }
            }
            val workers = Executors.newFixedThreadPool(2)
            try {
                val outgoing = workers.submit<PeerCheckpoint> { a.runtime.synchronize(b.id, transport) }
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val inbound = workers.submit<String> {
                    a.runtime.exchange("Bearer $tokenForA", messages.encodeRequest(
                        SyncRequest("inbound", b.id, 0, SyncBatch(0, 1, listOf(remote)))))
                }
                messages.decodeResponse(inbound.get(3, TimeUnit.SECONDS)).acceptedThrough shouldBe 1
                release.countDown()
                val failure = shouldThrow<ExecutionException> { outgoing.get(3, TimeUnit.SECONDS) }
                (failure.cause is IllegalArgumentException) shouldBe true
                a.journal.checkpoint(b.id) shouldBe PeerCheckpoint(0, 1)
                a.journal.records().size shouldBe 2
            } finally {
                release.countDown()
                workers.shutdownNow()
                workers.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
            }
        } }
    }
})
