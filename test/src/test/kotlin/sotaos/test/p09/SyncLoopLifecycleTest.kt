package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncLoopLifecycleTest : FunSpec({
    test("retry delay doubles to its cap and invalid scheduling settings fail fast") {
        val settings = SyncLoopSettings(retryBase = Duration.ofSeconds(1), maxBackoff = Duration.ofSeconds(4))
        (1..6).map { settings.retryDelay(it).seconds } shouldBe listOf(1L, 2L, 4L, 4L, 4L, 4L)
        shouldThrow<IllegalArgumentException> { settings.copy(interval = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { settings.copy(maxBackoff = Duration.ofMillis(1)) }
        shouldThrow<IllegalArgumentException> { settings.copy(progressDelay = Duration.ofDays(1)) }
    }
    test("shutdown interrupts in-flight network work and prevents any later scheduled attempt") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val calls = AtomicInteger()
            val stalled = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    calls.incrementAndGet()
                    entered.countDown()
                    try { CountDownLatch(1).await() } finally { interrupted.countDown() }
                    error("must be interrupted")
                }
            }
            val loop = P09SyncLoop(a.runtime, setOf(b.id), stalled, quickSyncSettings())
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                loop.close()
            }
            interrupted.count shouldBe 0
            loop.close()
            calls.get() shouldBe 1
            a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
        } }
    }
    test("revoked peer is never contacted by the background worker") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            a.runtime.peerProvisioning.revoke(invocation, authorityId, b.id, 1)
            val calls = AtomicInteger()
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    calls.incrementAndGet()
                    error("revoked peer must fail before network")
                }
            }
            P09SyncLoop(a.runtime, setOf(b.id), transport, quickSyncSettings()).use { loop ->
                waitForSync { (loop.snapshot()[b.id]?.consecutiveFailures ?: 0) >= 2 }
            }
            calls.get() shouldBe 0
            a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
            shouldThrow<IllegalArgumentException> { P09SyncLoop(a.runtime, setOf(a.id), transport) }
        } }
    }
})
