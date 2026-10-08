package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncSettingsUpdateTest : FunSpec({
    test("in-flight attempt uses newly published settings and pending retry is not duplicated or reset") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    entered.countDown()
                    release.await()
                    throw IOException("offline")
                }
            }
            val loop = P09SyncLoop(a.runtime, setOf(b.id), transport, quickSyncSettings())
            val updated = SyncLoopSettings(retryBase = Duration.ofMinutes(1), maxBackoff = Duration.ofMinutes(1))
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                loop.updateSettings(updated)
                release.countDown()
                waitForSync { loop.snapshot().getValue(b.id).phase == SyncPhase.BACKOFF }
                val pending = loop.snapshot().getValue(b.id)
                (Duration.between(Instant.now(), pending.nextAttemptAt).seconds >= MIN_REMAINING_SECONDS) shouldBe true
                loop.updateSettings(quickSyncSettings())
                loop.snapshot().getValue(b.id).nextAttemptAt shouldBe pending.nextAttemptAt
                loop.snapshot().getValue(b.id).attempts shouldBe 1L
                a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
            } finally {
                release.countDown()
                loop.close()
            }
            shouldThrow<IllegalStateException> { loop.updateSettings(updated) }
        } }
    }
})

private const val MIN_REMAINING_SECONDS = 45
