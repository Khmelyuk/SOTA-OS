package sotaos.application.ports

import sotaos.domain.shared.EventId

/** Local governance decision about one exact record, not proof of its claimed creation time. */
data class HistoricalRecordApproval(
    val eventId: EventId,
    val canonicalRecord: String,
    val key: ActorSigningKeyRecord,
    val audit: ProvisioningAudit,
    val evidenceReference: String
)

/** Trusted infrastructure; approvals, revocations and provisioning audit share one transaction. */
interface HistoricalApprovalRepository {
    fun find(eventId: EventId): HistoricalRecordApproval?
    fun revocation(eventId: EventId): ProvisioningAudit?
    fun save(approval: HistoricalRecordApproval)
    fun revoke(eventId: EventId, audit: ProvisioningAudit)
}
