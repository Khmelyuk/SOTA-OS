package sotaos.security

import sotaos.application.ports.*
import sotaos.application.services.ProvisioningAuthorization
import sotaos.domain.shared.*

class PeerProvisioningService(
    private val peers: PeerTrustRepository,
    private val authorization: ProvisioningAuthorization,
    private val localNode: SotaId
) {
    fun enroll(invocation: ProtocolInvocation, authority: AuthorityId, policy: PeerTrustPolicy): String =
        peers.transaction {
            require(policy.peer != localNode) { "The local node cannot enroll as a remote peer." }
            require(peers.find(policy.peer) == null) { "Peer already registered, including revoked peers." }
            val audit = authorize(invocation, authority, "peer.enroll", policy)
            val token = PeerCredentials.generate()
            peers.save(TrustedPeer(policy, PeerCredentials.hash(token)))
            peers.appendAudit(audit)
            token
        }

    fun rotate(invocation: ProtocolInvocation, authority: AuthorityId, peer: SotaId,
        expectedRevision: Long): String = peers.transaction {
        val current = current(peer, expectedRevision)
        val audit = authorize(invocation, authority, "peer.rotate", current.policy)
        val token = PeerCredentials.generate()
        peers.save(current.copy(credentialHash = PeerCredentials.hash(token), revision = current.revision + 1))
        peers.appendAudit(audit)
        token
    }

    fun revoke(invocation: ProtocolInvocation, authority: AuthorityId, peer: SotaId,
        expectedRevision: Long) = peers.transaction {
        val current = current(peer, expectedRevision)
        val audit = authorize(invocation, authority, "peer.revoke", current.policy)
        peers.save(current.copy(active = false, revision = current.revision + 1))
        peers.appendAudit(audit)
    }

    private fun current(peer: SotaId, revision: Long): TrustedPeer {
        val current = requireNotNull(peers.find(peer)) { "Unknown peer." }
        require(current.active && current.revision == revision) { "Revoked peer or stale revision." }
        return current
    }

    private fun authorize(invocation: ProtocolInvocation, authority: AuthorityId,
        operation: String, policy: PeerTrustPolicy): ProvisioningAudit = authorization.authorize(
        invocation, authority, operation, "peer:${policy.peer.value}", policy.actors + SubjectRef.Sota(policy.peer)
    )
}
