package sotaos.protocol

import kotlinx.serialization.json.*
import sotaos.domain.shared.*
import java.time.Instant

internal object SyncJsonValues {
    fun subject(value: SubjectRef): JsonObject = when (value) {
        is SubjectRef.Person -> reference("PERSON", value.id.value)
        is SubjectRef.Core -> reference("CORE", value.id.value)
        is SubjectRef.Sota -> reference("SOTA", value.id.value)
        is SubjectRef.Agent -> reference("AGENT", value.id.value)
    }

    fun subject(value: JsonObject): SubjectRef = when (value.text("kind")) {
        "PERSON" -> SubjectRef.Person(PersonId(value.text("id")))
        "CORE" -> SubjectRef.Core(CoreId(value.text("id")))
        "SOTA" -> SubjectRef.Sota(SotaId(value.text("id")))
        "AGENT" -> SubjectRef.Agent(AgentId(value.text("id")))
        else -> error("Unknown subject kind.")
    }

    fun context(value: Context): JsonObject = buildJsonObject {
        put("domain", value.domain); put("mission", value.mission?.value); put("description", value.description)
    }

    fun context(value: JsonObject): Context = Context(
        value.text("domain"), value.optional("mission")?.let(::MissionId), value.text("description")
    )

    fun provenance(value: Provenance): JsonObject = buildJsonObject {
        put("source", value.sourceEventId?.value); put("author", subject(value.author))
        put("recorded_at", value.recordedAt.toString())
        put("derived_from", JsonArray(value.derivedFrom.map(::JsonPrimitive)))
    }

    fun provenance(value: JsonObject): Provenance = Provenance(
        value.optional("source")?.let(::EventId), subject(value.getValue("author").jsonObject),
        Instant.parse(value.text("recorded_at")),
        value.getValue("derived_from").jsonArray.map { it.jsonPrimitive.content }
    )

    fun payload(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "Payload object keys must be strings." }
            JsonObject(value.entries.associate { it.key as String to payload(it.value) }.toSortedMap())
        }
        is Set<*> -> JsonArray(value.map(::payload).sortedBy { it.toString() })
        is Iterable<*> -> JsonArray(value.map(::payload))
        else -> error("Unsupported event payload value: ${value.javaClass.name}.")
    }

    fun payload(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { payload(it.value) }
        is JsonArray -> value.map(::payload)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.longOrNull != null -> value.long
            else -> value.double.also { require(it.isFinite()) }
        }
    }

    private fun reference(kind: String, id: String): JsonObject = buildJsonObject {
        put("kind", kind); put("id", id)
    }
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.optional(key: String): String? = getValue(key).jsonPrimitive.contentOrNull
