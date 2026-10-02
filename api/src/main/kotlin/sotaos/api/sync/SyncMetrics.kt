package sotaos.api.sync

/** Process-local completed attempts. Durations use a monotonic clock and include local validation and gate waits. */
data class SyncMetrics(
    val successes: Long = 0,
    val failures: Long = 0,
    val cancellations: Long = 0,
    val totalDurationNanos: Long = 0,
    val lastDurationNanos: Long? = null,
    val failuresByCategory: Map<SyncFailure, Long> = emptyMap()
) {
    internal fun completed(durationNanos: Long, failure: SyncFailure?): SyncMetrics = copy(
        successes = successes + if (failure == null) 1 else 0,
        failures = failures + if (failure != null) 1 else 0,
        totalDurationNanos = totalDurationNanos + durationNanos,
        lastDurationNanos = durationNanos,
        failuresByCategory = if (failure == null) failuresByCategory else
            failuresByCategory + (failure to ((failuresByCategory[failure] ?: 0) + 1))
    )

    internal fun cancelled(durationNanos: Long): SyncMetrics = copy(
        cancellations = cancellations + 1,
        totalDurationNanos = totalDurationNanos + durationNanos,
        lastDurationNanos = durationNanos
    )
}
