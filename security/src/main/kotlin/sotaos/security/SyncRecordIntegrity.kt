package sotaos.security

import sotaos.application.sync.SyncRecordCodec
import sotaos.domain.sync.SyncRecord
import java.security.MessageDigest

/** P09 strict profile: binds event content AND immutable sync metadata, with domain separation. */
class SyncRecordIntegrity(private val codec: SyncRecordCodec) {
    fun hash(record: SyncRecord): String {
        val unsigned = record.copy(event = record.event.copy(contentHash = "", signature = null))
        val canonical = "SOTA-P09-SIGNED-RECORD-v1\n" + codec.encode(unsigned)
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Call before first persistence; never rewrite or re-sign an already recorded Event. */
    fun sign(record: SyncRecord, signer: EventSigner): SyncRecord {
        val hash = hash(record)
        return record.copy(event = record.event.copy(contentHash = hash, signature = signer.sign(hash)))
    }

    fun check(record: SyncRecord) {
        require(record.event.contentHash == hash(record)) { "Event content or sync metadata was altered." }
    }
}
