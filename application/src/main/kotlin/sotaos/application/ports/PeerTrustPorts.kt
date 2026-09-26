package sotaos.application.ports

import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.*
import java.time.Instant
import sotaos.domain.sync.SyncEntity

/** Explicit sharing and origin-author bindings; no trust-on-first-use. */
data class PeerTrustPolicy(
    val peer: SotaId,
    val actors: Set<SubjectRef>,
    val contexts: Set<Context>,
    val directions: Set<SyncDirection>,
    val importOrigins: Set<SotaId>,
    val exportOrigins: Set<SotaId>,
    val assertionEntities: Set<SyncEntity> = emptySet()
) {
    init {
        require(peer.value.isNotBlank() && actors.isNotEmpty() && contexts.isNotEmpty())
        require(actors.none { it is SubjectRef.Agent })
        require(directions.isNotEmpty())
    }
}

data class TrustedPeer(
    val policy: PeerTrustPolicy,
    val credentialHash: String,
    val revision: Long = 1,
    val active: Boolean = true
)

data class ProvisioningAudit(
    val target: String,
    val operation: String,
    val actor: SubjectRef,
    val authority: AuthorityId,
    val purpose: String,
    val occurredAt: Instant
)

/** Mutations are infrastructure ports; expose only the authorized provisioning service to callers. */
interface PeerTrustRepository {
    fun <T> transaction(block: () -> T): T
    fun find(peer: SotaId): TrustedPeer?
    fun all(): List<TrustedPeer>
    fun save(peer: TrustedPeer)
    fun appendAudit(audit: ProvisioningAudit)
    fun audit(): List<ProvisioningAudit>
}
