package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightHistoricalApprovalRepository(private val db: SotaOsDatabase) : HistoricalApprovalRepository {
    override fun find(eventId: EventId): HistoricalRecordApproval? = db.historicalApprovalQueries
        .findHistoricalApproval(eventId.value).executeAsOneOrNull()?.let {
            HistoricalRecordApproval(eventId, it.canonical_record,
                ActorSigningKeyRecord(ValueJsonMapping.subject(it.actor_kind, it.actor_id),
                    it.key_id, it.public_key_base64),
                ProvisioningAudit(it.target, "history.approve",
                    ValueJsonMapping.subject(it.operator_kind, it.operator_id),
                    AuthorityId(it.authority_id), it.purpose, Instant.parse(it.approved_at)), it.evidence_reference)
        }

    override fun revocation(eventId: EventId): ProvisioningAudit? = db.historicalApprovalQueries
        .findHistoricalRevocation(eventId.value).executeAsOneOrNull()?.let {
            ProvisioningAudit(it.target, "history.revoke", ValueJsonMapping.subject(it.operator_kind, it.operator_id),
                AuthorityId(it.authority_id), it.purpose, Instant.parse(it.revoked_at))
        }

    override fun save(approval: HistoricalRecordApproval) {
        val (actorKind, actorId) = ValueJsonMapping.subject(approval.key.actor)
        val (operatorKind, operatorId) = ValueJsonMapping.subject(approval.audit.actor)
        db.historicalApprovalQueries.insertHistoricalApproval(approval.eventId.value, approval.canonicalRecord,
            actorKind, actorId, approval.key.keyId, approval.key.publicKeyBase64, approval.audit.target,
            operatorKind, operatorId, approval.audit.authority.value, approval.audit.purpose,
            approval.audit.occurredAt.toString(), approval.evidenceReference)
    }

    override fun revoke(eventId: EventId, audit: ProvisioningAudit) {
        val (kind, id) = ValueJsonMapping.subject(audit.actor)
        db.historicalApprovalQueries.insertHistoricalRevocation(eventId.value, audit.target, kind, id,
            audit.authority.value, audit.purpose, audit.occurredAt.toString())
    }
}
