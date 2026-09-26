package sotaos.security

import sotaos.application.ports.*
import sotaos.application.services.ProvisioningAuthorization
import sotaos.domain.shared.*

/** Key updates and actor/authority audit must use repositories sharing one database transaction. */
class ActorKeyProvisioningService(
    private val keys: ActorSigningKeyRepository,
    private val peers: PeerTrustRepository,
    private val authorization: ProvisioningAuthorization
) {
    fun enroll(invocation: ProtocolInvocation, authority: AuthorityId, record: ActorSigningKeyRecord) =
        peers.transaction {
            require(keys.findByActor(record.actor) == null) { "Actor already has a key." }
            save(invocation, authority, record, "key.enroll")
        }

    fun rotate(invocation: ProtocolInvocation, authority: AuthorityId, record: ActorSigningKeyRecord,
        expectedKeyId: String) = peers.transaction {
        val current = requireNotNull(keys.findByActor(record.actor))
        require(current.keyId == expectedKeyId && current.keyId != record.keyId) { "Stale or reused key ID." }
        save(invocation, authority, record, "key.rotate")
    }

    fun revoke(invocation: ProtocolInvocation, authority: AuthorityId, actor: SubjectRef,
        expectedKeyId: String) = peers.transaction {
        val audit = authorize(invocation, authority, actor, "key.revoke")
        keys.revoke(actor, expectedKeyId, audit.occurredAt)
        peers.appendAudit(audit)
    }

    private fun save(invocation: ProtocolInvocation, authority: AuthorityId,
        record: ActorSigningKeyRecord, operation: String) {
        val audit = authorize(invocation, authority, record.actor, operation)
        Ed25519EventSigner.fromPublicKeyEncoded(record.publicKeyBase64)
        require(keys.auditByActor(record.actor).none { it.keyId == record.keyId }) { "Key ID was already used." }
        keys.save(record, audit.occurredAt)
        peers.appendAudit(audit)
    }

    private fun authorize(invocation: ProtocolInvocation, authority: AuthorityId,
        actor: SubjectRef, operation: String): ProvisioningAudit {
        require(actor !is SubjectRef.Agent)
        return authorization.authorize(invocation, authority, operation, actorKeyTarget(actor), setOf(actor))
    }
}

fun actorKeyTarget(actor: SubjectRef): String = when (actor) {
    is SubjectRef.Person -> "key:PERSON:${actor.id.value}"
    is SubjectRef.Core -> "key:CORE:${actor.id.value}"
    is SubjectRef.Sota -> "key:SOTA:${actor.id.value}"
    is SubjectRef.Agent -> "key:AGENT:${actor.id.value}"
}
