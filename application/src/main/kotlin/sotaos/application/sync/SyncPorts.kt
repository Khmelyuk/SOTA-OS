package sotaos.application.sync

import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.*

/** Cursors describe contiguous positions in the sending node's journal, never wall-clock time. */
data class SyncBatch(val after: Long, val through: Long, val records: List<SyncRecord>) {
    init {
        require(after >= 0 && through >= after)
        require(through - after == records.size.toLong()) { "Non-contiguous sync batch." }
        require(records.size <= MAX_SYNC_BATCH)
    }
}

const val MAX_SYNC_BATCH = 256

data class PeerCheckpoint(val sent: Long = 0, val received: Long = 0)
data class SyncRequest(val id: String, val sender: SotaId, val receivedThrough: Long, val batch: SyncBatch)
data class SyncResponse(val requestId: String, val sender: SotaId, val acceptedThrough: Long, val batch: SyncBatch)

interface SyncTransport {
    fun exchange(peer: SotaId, request: SyncRequest): SyncResponse
}

/** Must authenticate/authorize the peer and validate origin, integrity and event risk before admission. */
fun interface SyncAdmission {
    fun check(peer: SotaId, direction: SyncDirection, records: List<SyncRecord>)
}

enum class SyncDirection { IMPORT, EXPORT }

interface SyncRecordCodec {
    fun encode(record: SyncRecord): String
    fun decode(encoded: String): SyncRecord
}

interface SyncMessageCodec {
    fun encodeRequest(request: SyncRequest): String
    fun decodeRequest(encoded: String): SyncRequest
    fun encodeResponse(response: SyncResponse): String
    fun decodeResponse(encoded: String): SyncResponse
}

/** One transaction covers event append, sync metadata, conflict records, and checkpoint updates. */
interface SyncRepository {
    fun <T> transaction(block: () -> T): T
    fun records(): List<SyncRecord>
    fun append(record: SyncRecord)
    fun batch(after: Long): SyncBatch
    fun checkpoint(peer: SotaId): PeerCheckpoint
    fun saveCheckpoint(peer: SotaId, checkpoint: PeerCheckpoint)
    fun saveConflict(conflict: SyncConflict)
    fun conflicts(): List<SyncConflict>
    fun untrackedEvents(): List<Event>
}
