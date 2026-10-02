package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncMetricsTest : FunSpec({
    test("failure recovery and shutdown record distinct outcomes and retain immutable metrics snapshots") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val token = b.enroll(a)
            val calls = AtomicInteger()
            val entered = CountDownLatch(1)
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse =
                    when (calls.incrementAndGet()) {
                    1 -> throw IOException("secret endpoint")
                    2 -> messages.decodeResponse(b.runtime.exchange("Bearer $token", messages.encodeRequest(request)))
                    else -> {
                        entered.countDown()
                        CountDownLatch(1).await()
                        error("must be interrupted")
                    }
                }
            }
            val loop = P09SyncLoop(a.runtime, setOf(b.id), transport, quickSyncSettings())
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val running = loop.snapshot().getValue(b.id)
                running.attempts shouldBe 3L
                running.phase shouldBe SyncPhase.RUNNING
                running.failure shouldBe null
                running.consecutiveFailures shouldBe 0
                running.metrics.successes shouldBe 1L
                running.metrics.failures shouldBe 1L
                running.metrics.cancellations shouldBe 0L
                running.metrics.failuresByCategory shouldBe mapOf(SyncFailure.NETWORK to 1L)
                (running.metrics.lastDurationNanos!! > 0) shouldBe true
                (running.metrics.totalDurationNanos >= running.metrics.lastDurationNanos!!) shouldBe true
                loop.close()
                val stopped = loop.snapshot().getValue(b.id)
                stopped.phase shouldBe SyncPhase.STOPPED
                stopped.metrics.cancellations shouldBe 1L
                stopped.metrics.successes shouldBe 1L
                stopped.metrics.failures shouldBe 1L
                (stopped.metrics.totalDurationNanos > running.metrics.totalDurationNanos) shouldBe true
                stopped.attempts shouldBe stopped.metrics.successes + stopped.metrics.failures +
                    stopped.metrics.cancellations
                running.metrics.cancellations shouldBe 0L
                stopped.metrics.toString().contains("secret") shouldBe false
            } finally { loop.close() }
        } }
    }
    test("new loop resets process metrics without changing durable checkpoints") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse = throw IOException("offline")
            }
            val settings = quickSyncSettings().copy(
                retryBase = Duration.ofMinutes(1), maxBackoff = Duration.ofMinutes(1))
            repeat(2) {
                P09SyncLoop(a.runtime, setOf(b.id), transport, settings).use { loop ->
                    waitForSync { loop.snapshot().getValue(b.id).phase == SyncPhase.BACKOFF }
                    val status = loop.snapshot().getValue(b.id)
                    status.attempts shouldBe 1L
                    status.metrics.failures shouldBe 1L
                    status.metrics.successes shouldBe 0L
                    status.metrics.failuresByCategory shouldBe mapOf(SyncFailure.NETWORK to 1L)
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
                }
            }
        } }
    }
})
