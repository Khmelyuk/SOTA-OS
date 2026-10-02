package sotaos.api.sync

import sotaos.application.sync.PeerCheckpoint
import sotaos.sync.SyncHttpException
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

enum class SyncPhase { WAITING, RUNNING, BACKOFF, STOPPED }
enum class SyncFailure { TLS, TIMEOUT, NETWORK, HTTP_REJECTED, LOCAL_VALIDATION, UNKNOWN }

/** Safe in-memory diagnostics; timestamps are observations, not durable cursors or scheduling guarantees. */
data class PeerSyncStatus(
    val attempts: Long = 0,
    val consecutiveFailures: Int = 0,
    val lastCheckpoint: PeerCheckpoint? = null,
    val phase: SyncPhase = SyncPhase.WAITING,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val nextAttemptAt: Instant? = null,
    val failure: SyncFailure? = null,
    val metrics: SyncMetrics = SyncMetrics()
)

/** Never retain exception messages, response bodies, credentials or endpoint URLs. */
internal fun syncFailure(error: Throwable): SyncFailure {
    var cause = error
    repeat(MAX_WRAPPERS) {
        if (cause is ExecutionException || cause is CompletionException) cause = cause.cause ?: cause
    }
    return when (cause) {
        is SSLException -> SyncFailure.TLS
        is TimeoutException, is HttpTimeoutException -> SyncFailure.TIMEOUT
        is SyncHttpException -> SyncFailure.HTTP_REJECTED
        is IOException -> SyncFailure.NETWORK
        is IllegalArgumentException -> SyncFailure.LOCAL_VALIDATION
        else -> SyncFailure.UNKNOWN
    }
}

private const val MAX_WRAPPERS = 8
