package sotaos.api.cli

import sotaos.api.sync.PeerSyncStatus

/** One configured outbound peer; fixed field values only, never credentials or exception text. */
fun formatSyncStatus(status: PeerSyncStatus): String = with(status) {
    "P09 status: phase=$phase attempts=$attempts failures=$consecutiveFailures " +
        "reason=${failure ?: "NONE"} sent=${lastCheckpoint?.sent ?: "UNKNOWN"} " +
        "received=${lastCheckpoint?.received ?: "UNKNOWN"} " +
        "lastAttempt=${lastAttemptAt ?: "NEVER"} lastSuccess=${lastSuccessAt ?: "NEVER"} " +
        "nextAttempt=${nextAttemptAt ?: "NONE"}"
}
