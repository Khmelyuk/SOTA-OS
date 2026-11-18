package sotaos.api.memory

import sotaos.api.sync.P09Runtime
import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.security.ActorKeyDirectory
import sotaos.security.EventSigner
import sotaos.security.LocalSyncIdentity

internal data class MemoryFact(val type: String, val entityId: String, val snapshot: String)

/** Called inside the enclosing P07 entity transaction; current keys are required for every new fact. */
internal class SignedMemoryJournal(
    store: SqlDelightStore,
    private val local: LocalSyncIdentity,
    private val sync: P09Runtime,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val signers: (SubjectRef) -> EventSigner
) {
    private val keys = SqlDelightRepositories(store.database).actorSigningKeys

    fun append(invocation: ProtocolInvocation, fact: MemoryFact, parents: List<Event>, reason: String? = null) {
        val actor = invocation.actor
        val at = clock.now()
        require(parents.isNotEmpty() && parents.all { it.context == parents.first().context })
        require(parents.all { !it.timestamp.isAfter(at) }) { "P07 fact cannot predate its sources." }
        val verifier = requireNotNull(ActorKeyDirectory.fromRecords(keys.findAll()).signerFor(actor)) {
            "Provision a current actor key before producing a signed P07 fact."
        }
        val event = Event(EventId(ids.next()), fact.type, actor, at, parents.first().context, null, null,
            mapOf("entityId" to fact.entityId, "snapshot" to fact.snapshot,
                "purpose" to invocation.purpose, "reason" to reason), null,
            Provenance(parents.first().id, actor, at, parents.map { it.id.value }), "", null)
        val record = sync.integrity.sign(SyncRecord(event, local.node, parents.map { it.id }.toSet()), signers(actor))
        require(verifier.verify(record.event.contentHash, requireNotNull(record.event.signature))) {
            "P07 signer does not match the current actor key."
        }
        sync.recordLocal(record)
    }
}
