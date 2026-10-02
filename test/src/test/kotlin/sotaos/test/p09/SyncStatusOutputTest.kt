package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.cli.formatSyncStatus
import sotaos.api.sync.*
import sotaos.application.sync.PeerCheckpoint
import java.time.Instant

class SyncStatusOutputTest : FunSpec({
    test("initial status does not claim a successful exchange or a zero durable checkpoint") {
        formatSyncStatus(PeerSyncStatus()) shouldBe
            "P09 status: phase=WAITING attempts=0 failures=0 reason=NONE sent=UNKNOWN received=UNKNOWN " +
            "lastAttempt=NEVER lastSuccess=NEVER successes=0 failedTotal=0 cancelled=0 " +
            "durationNanosTotal=0 lastDurationNanos=NONE nextAttempt=NONE"
    }
    test("backoff output retains the last successful checkpoint and reports UTC schedule") {
        val time = Instant.parse("2026-10-01T12:00:00Z")
        val status = PeerSyncStatus(2, 1, PeerCheckpoint(1, 2), SyncPhase.BACKOFF,
            time, time.minusSeconds(1), time.plusSeconds(1), SyncFailure.NETWORK)
        formatSyncStatus(status) shouldBe
            "P09 status: phase=BACKOFF attempts=2 failures=1 reason=NETWORK sent=1 received=2 " +
            "lastAttempt=2026-10-01T12:00:00Z lastSuccess=2026-10-01T11:59:59Z " +
            "successes=0 failedTotal=0 cancelled=0 durationNanosTotal=0 lastDurationNanos=NONE " +
            "nextAttempt=2026-10-01T12:00:01Z"
    }
})
