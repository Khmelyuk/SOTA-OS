package sotaos.protocol

import kotlinx.serialization.json.*
import sotaos.domain.memory.Experience
import sotaos.domain.memory.Knowledge

/** Complete version-1 snapshots carried by signed P07 facts; no implicit remote entity replay. */
class JsonMemorySnapshotCodec {
    fun encode(value: Experience): String = buildJsonObject {
        put("version", 1); put("kind", "EXPERIENCE"); put("id", value.id.value)
        put("events", JsonArray(value.derivedFromEvents.map { JsonPrimitive(it.value) }))
        put("context", SyncJsonValues.context(value.context))
        put("whatWasTried", value.whatWasTried); put("outcome", value.outcome)
        put("conclusion", value.conclusion); put("applicability", value.applicabilityConditions)
        put("confidence", value.confidence.name); put("provenance", SyncJsonValues.provenance(value.provenance))
    }.toString()

    fun encode(value: Knowledge): String = buildJsonObject {
        put("version", 1); put("kind", "KNOWLEDGE"); put("id", value.id.value)
        put("statement", value.statement)
        put("experiences", JsonArray(value.derivedFromExperience.map { JsonPrimitive(it.value) }))
        put("evidence", JsonArray(value.evidence.map { JsonPrimitive(it.value) }))
        put("context", SyncJsonValues.context(value.context)); put("status", value.status.name)
        put("knowledgeVersion", value.version); put("supersedes", value.supersedes?.value)
        put("provenance", SyncJsonValues.provenance(value.provenance))
    }.toString()
}
