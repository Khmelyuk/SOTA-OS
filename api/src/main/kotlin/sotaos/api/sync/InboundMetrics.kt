package sotaos.api.sync

/** Handler-level observations, not evidence that a peer received a response or committed its checkpoint. */
data class InboundMetrics(
    val started: Long = 0,
    val completed: Long = 0,
    val aborted: Long = 0,
    val totalDurationNanos: Long = 0,
    val lastDurationNanos: Long? = null,
    val responses: Map<Int, Long> = emptyMap()
) {
    val inFlight: Long get() = started - completed
}

internal class InboundMetricsRecorder {
    private var state = InboundMetrics()

    @Synchronized fun started() { state = state.copy(started = state.started + 1) }
    @Synchronized fun snapshot(): InboundMetrics = state
    @Synchronized fun finished(status: Int?, duration: Long) {
        state = state.copy(completed = state.completed + 1,
            aborted = state.aborted + if (status == null) 1 else 0,
            totalDurationNanos = state.totalDurationNanos + duration,
            lastDurationNanos = duration,
            responses = if (status == null) state.responses else
                state.responses + (status to ((state.responses[status] ?: 0) + 1)))
    }
}
