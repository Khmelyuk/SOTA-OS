package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.memory.Event
import sotaos.domain.shared.*

internal fun appendExitEvent(recorder: ExitEventRecorder, process: ExitProcess, transition: ExitTransition) {
    val actor = SubjectRef.Person(transition.actor)
    val payload = mapOf("exitId" to process.id, "coreId" to process.target.core.value,
        "personId" to process.target.person.value, "stage" to transition.stage.name)
    val id = EventId("exit:${process.id}:${transition.stage}")
    val type = "P10.${transition.stage}"
    val context = Context("core-exit", description = "core:${process.target.core.value}")
    val provenance = Provenance(sourceEventId = null, author = actor, recordedAt = transition.occurredAt)
    val hash = exitDigest(PayloadJsonMapping.payload(mapOf(
        "id" to id.value, "type" to type, "actor" to transition.actor.value,
        "timestamp" to transition.occurredAt.toString(), "payload" to payload,
        "context" to JsonMapping.context(context), "provenance" to JsonMapping.provenance(provenance)
    )))
    val previous = ExitStage.entries.getOrNull(transition.stage.ordinal - 1)
    val parents = previous?.let { setOf(EventId("exit:${process.id}:$it")) } ?: emptySet()
    recorder.append(Event(id, type, actor, transition.occurredAt, context,
        null, null, payload, null, provenance, hash, null), parents)
}
