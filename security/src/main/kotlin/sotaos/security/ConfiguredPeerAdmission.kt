package sotaos.security

import sotaos.application.sync.MAX_SYNC_BATCH
import sotaos.application.sync.SyncAdmission
import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.SotaId
import sotaos.domain.sync.SyncRecord

class ConfiguredPeerAdmission(
    private val trustedPeers: Set<SotaId>,
    private val recordPolicy: SyncAdmission,
    private val allowedDirections: Set<SyncDirection> = SyncDirection.entries.toSet()
) : SyncAdmission {
    init {
        require(trustedPeers.isNotEmpty()) { "At least one trusted sync peer is required." }
        require(allowedDirections.isNotEmpty()) { "At least one sync direction must be allowed." }
    }

    override fun check(peer: SotaId, direction: SyncDirection, records: List<SyncRecord>) {
        require(peer in trustedPeers) { "Peer ${peer.value} is not trusted for sync." }
        require(direction in allowedDirections) { "Sync direction $direction is not allowed for peer ${peer.value}." }
        require(records.size <= MAX_SYNC_BATCH) { "Sync admission received too many records." }
        recordPolicy.check(peer, direction, records)
    }
}
