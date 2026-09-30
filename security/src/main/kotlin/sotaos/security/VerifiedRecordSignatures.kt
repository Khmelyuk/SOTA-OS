package sotaos.security

import sotaos.application.ports.*
import sotaos.application.sync.SyncRecordCodec
import sotaos.domain.sync.SyncRecord

/** Old-key exceptions require exact local history or an independently governed exact-record approval. */
class VerifiedRecordSignatures(
    private val keys: ActorSigningKeyRepository,
    private val history: VerifiedRecordRepository,
    private val codec: SyncRecordCodec,
    private val integrity: SyncRecordIntegrity,
    private val clock: Clock,
    private val approvals: HistoricalApprovalRepository? = null
) {
    fun historicalSigner(record: SyncRecord): EventSigner? {
        val proof = history.find(record.event.id)
        val key = if (proof != null) {
            require(proof.canonicalRecord == codec.encode(record) && proof.key.actor == record.event.actor) {
                "Record differs from locally verified history."
            }
            proof.key
        } else {
            approval(record)?.key
        }
        return key?.let { Ed25519EventSigner.fromPublicKeyEncoded(it.publicKeyBase64) }
    }

    /** Call only inside the transaction that appends the event and its complete sync metadata. */
    fun remember(record: SyncRecord) {
        integrity.check(record)
        val signature = requireNotNull(record.event.signature) { "Signed record required." }
        if (history.find(record.event.id) != null) {
            require(requireNotNull(historicalSigner(record)).verify(record.event.contentHash, signature))
            return
        }
        // Re-read authorization at append, never rely on an earlier admission snapshot.
        val approval = approval(record)
        val key = requireNotNull(approval?.key ?: keys.findByActor(record.event.actor)) {
            "Current actor key or explicit historical approval required."
        }
        require(Ed25519EventSigner.fromPublicKeyEncoded(key.publicKeyBase64)
            .verify(record.event.contentHash, signature)) { "Record signature does not match the authorized key." }
        history.save(VerifiedRecord(record.event.id, codec.encode(record), key, clock.now(), approval?.audit?.target))
    }

    private fun approval(record: SyncRecord): HistoricalRecordApproval? {
        val approval = approvals?.find(record.event.id)?.takeIf { approvals.revocation(record.event.id) == null }
        approval?.let {
            require(it.canonicalRecord == codec.encode(record) && it.key.actor == record.event.actor) {
                "Record differs from historical approval."
            }
        }
        return approval
    }
}
