package sotaos.application.ports

import sotaos.domain.shared.EventId
import java.time.Instant

/** Local evidence of signature verification, not a sender-supplied timestamp or transferable credential. */
data class VerifiedRecord(
    val eventId: EventId,
    val canonicalRecord: String,
    val key: ActorSigningKeyRecord,
    val verifiedAt: Instant,
    val historicalApprovalTarget: String? = null
)

/** Trusted infrastructure; writes must share the event/journal transaction. */
interface VerifiedRecordRepository {
    fun find(eventId: EventId): VerifiedRecord?
    fun save(record: VerifiedRecord)
}
