package sotaos.persistence

import kotlinx.serialization.json.*

/** JSON conversion for heterogeneous event payloads. */
internal object PayloadJsonMapping {
    private val json = Json { encodeDefaults = true }

    fun payload(value: Map<String, Any?>): String = encode(buildJsonObject {
        value.forEach { (key, item) -> put(key, toJson(item)) }
    })

    fun payload(value: String): Map<String, Any?> = parse(value).jsonObject.mapValues { fromJson(it.value) }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) -> if (key is String) put(key, toJson(item)) }
        }
        is Iterable<*> -> JsonArray(value.map(::toJson))
        is Array<*> -> JsonArray(value.map(::toJson))
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { fromJson(it.value) }
        is JsonArray -> value.map(::fromJson)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.longOrNull != null -> value.long
            value.doubleOrNull != null -> value.double
            else -> value.content
        }
    }

    private fun encode(value: JsonElement): String = value.toString()
    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)
}
