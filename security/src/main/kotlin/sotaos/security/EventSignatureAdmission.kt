package sotaos.security

import sotaos.application.sync.SyncAdmission
import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.SubjectRef

class EventSignatureAdmission(
    private val delegate: SyncAdmission,
    private val signerFor: (SubjectRef) -> EventSigner?
) : SyncAdmission {
    constructor(delegate: SyncAdmission, actorSigners: Map<SubjectRef, EventSigner>) : this(delegate, actorSigners::get)
    constructor(delegate: SyncAdmission, directory: ActorKeyDirectory) : this(delegate, directory::signerFor)

    override fun check(peer: sotaos.domain.shared.SotaId, direction: SyncDirection,
        records: List<sotaos.domain.sync.SyncRecord>) {
        delegate.check(peer, direction, records)
        records.forEach { record ->
            val event = record.event
            val signer = signerFor(event.actor)
            val signature = event.signature
            if (signature == null) {
                require(signer == null) { "Unsigned event ${event.id.value} requires an actor signature." }
            } else {
                require(signer != null && signer.verify(event.contentHash, signature)) {
                    "Invalid or untrusted signature for event ${event.id.value}."
                }
            }
        }
    }
}
