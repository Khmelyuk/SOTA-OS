package sotaos.security

import sotaos.application.ports.*
import sotaos.application.sync.SyncRecordCodec
import sotaos.domain.sync.SyncRecord

/** Retirement preserves exact locally verified history, never authorizes unknown old-key records. */
class VerifiedRecordSignatures(
    private val keys: ActorSigningKeyRepository,
    private val history: VerifiedRecordRepository,
    private val codec: SyncRecordCodec,
    private val integrity: SyncRecordIntegrity,
    private val clock: Clock
) {
    fun historicalSigner(record: SyncRecord): EventSigner? {
        val proof = history.find(record.event.id) ?: return null
        require(proof.canonicalRecord == codec.encode(record) && proof.key.actor == record.event.actor) {
            "Record differs from locally verified history."
        }
        return Ed25519EventSigner.fromPublicKeyEncoded(proof.key.publicKeyBase64)
    }

    /** Call only inside the transaction that appends the event and its complete sync metadata. */
    fun remember(record: SyncRecord) {
        integrity.check(record)
        val signature = requireNotNull(record.event.signature) { "Signed record required." }
        val historical = historicalSigner(record)
        if (historical != null) {
            require(historical.verify(record.event.contentHash, signature))
            return
        }
        val key = requireNotNull(keys.findByActor(record.event.actor)) { "Current actor key required." }
        require(Ed25519EventSigner.fromPublicKeyEncoded(key.publicKeyBase64)
            .verify(record.event.contentHash, signature)) { "Record signature does not match current actor key." }
        history.save(VerifiedRecord(record.event.id, codec.encode(record), key, clock.now()))
    }
}
