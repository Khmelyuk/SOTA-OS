package sotaos.security

import sotaos.application.ports.*
import sotaos.application.services.ProvisioningAuthorization
import sotaos.application.sync.SyncRecordCodec
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord

/** Authenticated local governance only. Never exposed through a peer exchange or inferred from event time. */
class HistoricalRecordApprovalService(
    private val approvals: HistoricalApprovalRepository,
    private val peers: PeerTrustRepository,
    private val authorization: ProvisioningAuthorization,
    private val codec: SyncRecordCodec,
    private val integrity: SyncRecordIntegrity,
    private val localNode: SotaId
) {
    fun approve(invocation: ProtocolInvocation, authority: AuthorityId, record: SyncRecord,
        key: ActorSigningKeyRecord, evidenceReference: String): HistoricalRecordApproval = peers.transaction {
        val audit = authorize(invocation, authority, record, "history.approve")
        require(record.origin != localNode) { "Historical import approval cannot authorize a local producer." }
        require(key.actor == record.event.actor && key.actor !is SubjectRef.Agent)
        require(evidenceReference.isNotBlank()) { "Independent review evidence is required." }
        require(approvals.find(record.event.id) == null) { "An approval cannot be replaced, even after revocation." }
        integrity.check(record)
        val signature = requireNotNull(record.event.signature)
        require(Ed25519EventSigner.fromPublicKeyEncoded(key.publicKeyBase64)
            .verify(record.event.contentHash, signature)) { "Historical key does not verify this record." }
        val canonical = codec.encode(record)
        require(canonical.toByteArray(Charsets.UTF_8).size <= MAX_APPROVED_RECORD_BYTES)
        val approval = HistoricalRecordApproval(record.event.id, canonical, key, audit, evidenceReference)
        approvals.save(approval)
        peers.appendAudit(audit)
        approval
    }

    fun revoke(invocation: ProtocolInvocation, authority: AuthorityId, eventId: EventId) = peers.transaction {
        val approval = requireNotNull(approvals.find(eventId)) { "Unknown historical approval." }
        require(approvals.revocation(eventId) == null) { "Historical approval already revoked." }
        val record = codec.decode(approval.canonicalRecord)
        val audit = authorize(invocation, authority, record, "history.revoke")
        approvals.revoke(eventId, audit)
        peers.appendAudit(audit)
    }

    private fun authorize(invocation: ProtocolInvocation, authority: AuthorityId, record: SyncRecord,
        operation: String): ProvisioningAudit = authorization.authorize(invocation, authority, operation,
        historicalRecordTarget(record), setOf(record.event.actor, SubjectRef.Sota(record.origin)))

    private companion object {
        const val MAX_APPROVED_RECORD_BYTES = 4 * 1024 * 1024
    }
}

/** Integrity-checked digest binds the event and origin, parents and assertion; never a wildcard key grant. */
fun historicalRecordTarget(record: SyncRecord): String = "history:${record.event.contentHash}"
