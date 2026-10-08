package sotaos.api.sync

import sotaos.application.sync.SyncTransport
import sotaos.domain.shared.SotaId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SyncLoopSettings(
    val interval: Duration = Duration.ofSeconds(DEFAULT_INTERVAL_SECONDS),
    val retryBase: Duration = Duration.ofSeconds(1),
    val maxBackoff: Duration = Duration.ofMinutes(1),
    val progressDelay: Duration = Duration.ofMillis(DEFAULT_PROGRESS_MILLIS)
) {
    init {
        listOf(interval, retryBase, maxBackoff, progressDelay).forEach {
            require(!it.isNegative && it.toMillis() in 1..MAX_DELAY_MILLIS)
        }
        require(maxBackoff >= retryBase)
    }

    fun retryDelay(failures: Int): Duration {
        require(failures > 0)
        var millis = retryBase.toMillis()
        repeat((failures - 1).coerceAtMost(MAX_FAILURE_COUNT)) {
            millis = (millis * 2).coerceAtMost(maxBackoff.toMillis())
        }
        return Duration.ofMillis(millis)
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_PROGRESS_MILLIS = 100L
        const val MAX_DELAY_MILLIS = 3_600_000L
    }
}

/** One outbound worker, one scheduled task per configured peer, one bounded batch per attempt. */
class P09SyncLoop(
    private val runtime: P09Runtime,
    peers: Set<SotaId>,
    private val transport: SyncTransport,
    settings: SyncLoopSettings = SyncLoopSettings()
) : AutoCloseable {
    init {
        require(peers.isNotEmpty() && peers.size <= MAX_PEERS && runtime.nodeId !in peers)
    }
    @Volatile private var activeSettings = settings
    private val stopped = AtomicBoolean(false)
    private val states = ConcurrentHashMap(peers.associateWith { PeerSyncStatus() })
    private val worker = ScheduledThreadPoolExecutor(1).also { it.removeOnCancelPolicy = true }

    init {
        peers.forEach { schedule(it, Duration.ZERO) }
    }

    fun updateSettings(settings: SyncLoopSettings) {
        synchronized(stopped) {
            check(!stopped.get()) { "Cannot update a stopped sync loop." }
            activeSettings = settings
        }
    }

    fun snapshot(): Map<SotaId, PeerSyncStatus> = states.toMap()

    private fun schedule(peer: SotaId, delay: Duration) {
        // Serialize scheduling against shutdown so no rejected task escapes the worker.
        synchronized(stopped) {
            if (!stopped.get()) worker.schedule({ attempt(peer) }, delay.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    // A failed peer must not terminate the scheduler; expose only a safe category.
    @Suppress("TooGenericExceptionCaught")
    private fun attempt(peer: SotaId) {
        val began = System.nanoTime()
        val previous = states.getValue(peer)
        val started = previous.copy(attempts = previous.attempts + 1,
            phase = SyncPhase.RUNNING, lastAttemptAt = Instant.now(), nextAttemptAt = null)
        states[peer] = started
        val next = try {
            started.copy(consecutiveFailures = 0, lastCheckpoint = runtime.synchronize(peer, transport),
                lastSuccessAt = Instant.now(), failure = null, phase = SyncPhase.WAITING)
        } catch (_: InterruptedException) {
            states[peer] = started.copy(phase = SyncPhase.STOPPED,
                metrics = started.metrics.cancelled(System.nanoTime() - began))
            Thread.currentThread().interrupt()
            return
        } catch (failure: Exception) {
            started.copy(consecutiveFailures = (previous.consecutiveFailures + 1).coerceAtMost(MAX_FAILURE_COUNT),
                failure = syncFailure(failure), phase = SyncPhase.BACKOFF)
        }
        val settings = activeSettings
        val delay = when {
            next.consecutiveFailures > 0 -> settings.retryDelay(next.consecutiveFailures)
            next.lastCheckpoint != previous.lastCheckpoint -> settings.progressDelay
            else -> settings.interval
        }
        states[peer] = next.copy(nextAttemptAt = Instant.now().plus(delay),
            metrics = next.metrics.completed(System.nanoTime() - began, next.failure))
        schedule(peer, delay)
    }

    override fun close() {
        synchronized(stopped) {
            stopped.set(true)
            worker.shutdownNow()
        }
        check(worker.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) { "P09 outbound worker did not stop." }
        states.replaceAll { _, state -> state.copy(phase = SyncPhase.STOPPED, nextAttemptAt = null) }
    }

    private companion object {
        const val MAX_PEERS = 64
        const val CLOSE_SECONDS = 20L
    }
}

private const val MAX_FAILURE_COUNT = 30
