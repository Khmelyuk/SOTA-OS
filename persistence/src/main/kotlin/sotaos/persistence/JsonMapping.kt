package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.domain.agency.*
import sotaos.domain.collective.*
import sotaos.domain.memory.*
import sotaos.domain.relation.*
import sotaos.domain.rights.*
import sotaos.domain.shared.*
import java.time.Instant
import sotaos.persistence.ValueJsonMapping.subject

/** Explicit, versionable mappings for the JSON columns in Schema.sq. */
internal object JsonMapping {
    private val json = Json { encodeDefaults = true }

    fun context(value: Context): String = encode(buildJsonObject {
        put("domain", value.domain)
        value.mission?.let { put("mission", it.value) }
        put("description", value.description)
    })

    fun context(value: String): Context {
        val item = parse(value).jsonObject
        return Context(
            domain = item.getValue("domain").jsonPrimitive.content,
            mission = item["mission"]?.jsonPrimitive?.contentOrNull?.let(::MissionId),
            description = item["description"]?.jsonPrimitive?.content ?: ""
        )
    }

    fun scope(value: Scope): String = encode(buildJsonObject {
        put("actions", JsonArray(value.actions.sorted().map(::JsonPrimitive)))
        put("resources", JsonArray(value.resources.sorted().map(::JsonPrimitive)))
        put("description", value.description)
    })

    fun scope(value: String): Scope {
        val item = parse(value).jsonObject
        return Scope(
            actions = item.getValue("actions").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            resources = item.getValue("resources").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            description = item.getValue("description").jsonPrimitive.content
        )
    }

    fun provenance(value: Provenance): String = encode(buildJsonObject {
        value.sourceEventId?.let { put("sourceEventId", it.value) }
        val (kind, id) = subject(value.author)
        put("authorKind", kind)
        put("authorId", id)
        put("recordedAt", value.recordedAt.toString())
        put("derivedFrom", JsonArray(value.derivedFrom.map(::JsonPrimitive)))
    })

    fun provenance(value: String): Provenance {
        val item = parse(value).jsonObject
        return Provenance(
            sourceEventId = item["sourceEventId"]?.jsonPrimitive?.contentOrNull?.let(::EventId),
            author = subject(item.getValue("authorKind").jsonPrimitive.content,
                item.getValue("authorId").jsonPrimitive.content),
            recordedAt = Instant.parse(item.getValue("recordedAt").jsonPrimitive.content),
            derivedFrom = item.getValue("derivedFrom").jsonArray.map { it.jsonPrimitive.content }
        )
    }

    private fun encode(value: JsonElement): String = value.toString()
    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)
}
