package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.sync.SyncHttpException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

class SyncDiagnosticsTest : FunSpec({
    val cases = listOf(
        ExecutionException(SSLHandshakeException("secret-token")) to SyncFailure.TLS,
        ExecutionException(IOException("secret-endpoint")) to SyncFailure.NETWORK,
        TimeoutException("secret") to SyncFailure.TIMEOUT,
        SyncHttpException(401) to SyncFailure.HTTP_REJECTED,
        IllegalArgumentException("secret-record") to SyncFailure.LOCAL_VALIDATION,
        IllegalStateException("secret") to SyncFailure.UNKNOWN
    )
    cases.forEach { (error, category) ->
        test("diagnostics classify $category without exposing exception content") {
            HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
                a.enroll(b)
                val transport = object : SyncTransport {
                    override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse = throw error
                }
                val settings = quickSyncSettings().copy(retryBase = Duration.ofMinutes(1),
                    maxBackoff = Duration.ofMinutes(1))
                P09SyncLoop(a.runtime, setOf(b.id), transport, settings).use { loop ->
                    waitForSync { loop.snapshot().getValue(b.id).phase == SyncPhase.BACKOFF }
                    val status = loop.snapshot().getValue(b.id)
                    status.failure shouldBe category
                    status.attempts shouldBe 1L
                    status.consecutiveFailures shouldBe 1
                    (status.lastAttemptAt != null) shouldBe true
                    (status.nextAttemptAt!! > status.lastAttemptAt) shouldBe true
                    status.lastSuccessAt shouldBe null
                    status.lastCheckpoint shouldBe null
                    status.toString().contains("secret") shouldBe false
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
                }
            } }
        }
    }
    test("running attempt is visible and shutdown clears its pending schedule") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val entered = CountDownLatch(1)
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    entered.countDown()
                    CountDownLatch(1).await()
                    error("interrupted")
                }
            }
            val loop = P09SyncLoop(a.runtime, setOf(b.id), transport)
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val status = loop.snapshot().getValue(b.id)
                status.phase shouldBe SyncPhase.RUNNING
                status.attempts shouldBe 1L
                status.nextAttemptAt shouldBe null
            } finally { loop.close() }
            loop.snapshot().getValue(b.id).phase shouldBe SyncPhase.STOPPED
            loop.snapshot().getValue(b.id).nextAttemptAt shouldBe null
        } }
    }
})
