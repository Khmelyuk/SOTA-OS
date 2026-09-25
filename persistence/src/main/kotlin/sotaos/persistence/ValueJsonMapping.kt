package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.domain.agency.*
import sotaos.domain.collective.*
import sotaos.domain.memory.*
import sotaos.domain.relation.*
import sotaos.domain.rights.*
import sotaos.domain.shared.*
import java.time.Instant

/** JSON conversion for scalar collections, subject references, and validity. */
internal object ValueJsonMapping {
    private val json = Json { encodeDefaults = true }

    fun strings(values: Iterable<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    fun strings(value: String): List<String> = json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.content }

    fun subject(value: SubjectRef): Pair<String, String> = when (value) {
        is SubjectRef.Person -> "PERSON" to value.id.value
        is SubjectRef.Core -> "CORE" to value.id.value
        is SubjectRef.Sota -> "SOTA" to value.id.value
        is SubjectRef.Agent -> "AGENT" to value.id.value
    }

    fun subject(kind: String, id: String): SubjectRef = when (kind) {
        "PERSON" -> SubjectRef.Person(PersonId(id))
        "CORE" -> SubjectRef.Core(CoreId(id))
        "SOTA" -> SubjectRef.Sota(SotaId(id))
        "AGENT" -> SubjectRef.Agent(AgentId(id))
        else -> error("Unknown subject kind '$kind' in database")
    }

    fun validity(from: Instant, until: Instant?): String = encode(buildJsonObject {
        put("from", from.toString())
        until?.let { put("until", it.toString()) }
    })

    fun validity(value: String): Validity {
        val item = parse(value).jsonObject
        return Validity(
            from = Instant.parse(item.getValue("from").jsonPrimitive.content),
            until = item["until"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
        )
    }

    private fun encode(value: JsonElement): String = value.toString()
    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)
}
