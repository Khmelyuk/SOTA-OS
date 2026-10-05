package sotaos.api.cli

import sotaos.api.sync.InboundMetrics
import sotaos.api.sync.PeerSyncStatus
import sotaos.api.sync.SyncFailure
import sotaos.api.sync.SyncPhase

/** JSON schema 1: fixed field names and numbers only, without peer identities or request-derived labels. */
fun formatNodeMetrics(inbound: InboundMetrics, peers: Collection<PeerSyncStatus>): String {
    val incoming = numbers(linkedMapOf("started" to inbound.started, "completed" to inbound.completed,
        "inFlight" to inbound.inFlight, "aborted" to inbound.aborted,
        "durationNanosTotal" to inbound.totalDurationNanos))
    val outgoing = numbers(linkedMapOf("configuredPeers" to peers.size.toLong(),
        "attempts" to peers.sumOf { it.attempts }, "successes" to peers.sumOf { it.metrics.successes },
        "failures" to peers.sumOf { it.metrics.failures }, "cancellations" to peers.sumOf { it.metrics.cancellations },
        "durationNanosTotal" to peers.sumOf { it.metrics.totalDurationNanos }))
    val phases = numbers(SyncPhase.entries.associate { phase ->
        phase.name to peers.count { it.phase == phase }.toLong()
    })
    val failures = numbers(SyncFailure.entries.associate { category ->
        category.name to peers.sumOf { it.metrics.failuresByCategory[category] ?: 0 }
    })
    val responses = numbers(inbound.responses.toSortedMap().mapKeys { it.key.toString() })
    return "{\"version\":1,\"inbound\":{$incoming,\"responses\":{$responses}}," +
        "\"outbound\":{$outgoing,\"phases\":{$phases},\"failuresByCategory\":{$failures}}}"
}

private fun numbers(values: Map<String, Long>): String =
    values.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
