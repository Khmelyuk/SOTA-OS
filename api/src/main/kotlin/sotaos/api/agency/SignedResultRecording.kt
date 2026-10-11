package sotaos.api.agency

import sotaos.application.ports.EventStore
import sotaos.application.ports.IdGenerator
import sotaos.domain.agency.Action
import sotaos.domain.agency.Result
import sotaos.domain.memory.Event
import sotaos.domain.shared.EventId
import sotaos.domain.shared.Provenance
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.persistence.SqlDelightVerifiedRecordRepository
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.Ed25519EventSigner
import sotaos.security.LocalSyncIdentity
import sotaos.security.SyncRecordIntegrity

/** Invoked with the shared store gate held; no signing or result persistence may escape this transaction. */
internal class SignedResultRecording(
    private val store: SqlDelightStore,
    private val local: LocalSyncIdentity,
    private val events: EventStore,
    private val ids: IdGenerator
) {
    fun record(action: Action, create: () -> Result): Result = store.database.transactionWithResult {
        val parent = parent(action)
        val result = create()
        events.append(Event(EventId(ids.next()), "RESULT_RECORDED", action.actor, result.timestamp,
            action.context, action.authority.value, action.decision?.value,
            mapOf("actionId" to action.id.value, "outcome" to result.outcome.name,
                "description" to result.description), result.id.value,
            Provenance(parent.id, action.actor, result.timestamp), "", null))
        result
    }

    private fun parent(action: Action): Event {
        val codec = JsonSyncRecordCodec()
        val record = requireNotNull(SqlDelightSyncRepository(store.database, codec).records().singleOrNull {
            it.event.type == "ACTION_EXECUTED" && it.event.payload["actionId"] == action.id.value
        }) { "Signed Result requires exactly one journaled Action event; legacy history cannot be upgraded." }
        val event = record.event
        require(record.origin == local.node && event.actor == action.actor && event.actor in local.actors)
        require(event.context == action.context && event.authorityRef == action.authority.value &&
            event.decisionRef == action.decision?.value && event.timestamp == action.timestamp)
        SyncRecordIntegrity(codec).check(record)
        val proof = requireNotNull(SqlDelightVerifiedRecordRepository(store.database).find(event.id)) {
            "Action event must have local verification evidence."
        }
        require(proof.canonicalRecord == codec.encode(record) && proof.key.actor == event.actor)
        require(Ed25519EventSigner.fromPublicKeyEncoded(proof.key.publicKeyBase64)
            .verify(event.contentHash, requireNotNull(event.signature)))
        return event
    }
}
