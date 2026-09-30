package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.shared.EventId
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightVerifiedRecordRepository(private val db: SotaOsDatabase) : VerifiedRecordRepository {
    override fun find(eventId: EventId): VerifiedRecord? = db.verifiedRecordQueries
        .findVerifiedRecord(eventId.value).executeAsOneOrNull()?.let {
            VerifiedRecord(EventId(it.event_id), it.canonical_record,
                ActorSigningKeyRecord(ValueJsonMapping.subject(it.actor_kind, it.actor_id),
                    it.key_id, it.public_key_base64), Instant.parse(it.verified_at), it.historical_approval_target)
        }

    override fun save(record: VerifiedRecord) {
        val (kind, id) = ValueJsonMapping.subject(record.key.actor)
        db.verifiedRecordQueries.insertVerifiedRecord(record.eventId.value, record.canonicalRecord,
            kind, id, record.key.keyId, record.key.publicKeyBase64, record.verifiedAt.toString(),
            record.historicalApprovalTarget)
    }
}
