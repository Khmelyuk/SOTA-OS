package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightPeerTrustRepository(private val db: SotaOsDatabase) : PeerTrustRepository {
    override fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }
    override fun find(peer: SotaId): TrustedPeer? =
        db.peerTrustQueries.selectPeer(peer.value).executeAsOneOrNull()?.toDomain()
    override fun all(): List<TrustedPeer> = db.peerTrustQueries.selectPeers().executeAsList().map { it.toDomain() }
    override fun save(peer: TrustedPeer) {
        val policy = PeerPolicyJson.encode(peer.policy)
        val active = if (peer.active) 1L else 0L
        if (find(peer.policy.peer) == null) {
            db.peerTrustQueries.insertPeer(peer.policy.peer.value, policy, peer.credentialHash, peer.revision, active)
        } else {
            db.peerTrustQueries.updatePeer(policy, peer.credentialHash, peer.revision, active, peer.policy.peer.value)
        }
    }
    override fun appendAudit(audit: ProvisioningAudit) {
        val (kind, id) = ValueJsonMapping.subject(audit.actor)
        db.peerTrustQueries.appendProvisioningAudit(audit.target, audit.operation, kind, id,
            audit.authority.value, audit.purpose, audit.occurredAt.toString())
    }
    override fun audit(): List<ProvisioningAudit> =
        db.peerTrustQueries.selectProvisioningAudit().executeAsList().map {
            ProvisioningAudit(it.target, it.operation, ValueJsonMapping.subject(it.actor_kind, it.actor_id),
                AuthorityId(it.authority_id), it.purpose, Instant.parse(it.occurred_at))
        }
}

private fun Trusted_peer.toDomain(): TrustedPeer = TrustedPeer(
    PeerPolicyJson.decode(policy_json), credential_hash, revision, active == 1L
)
