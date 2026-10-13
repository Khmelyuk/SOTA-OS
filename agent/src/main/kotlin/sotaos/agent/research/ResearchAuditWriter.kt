package sotaos.agent.research

import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/** Serialize human attribution and provenance consistently for each research command. */
internal class ResearchAuditWriter(private val now: () -> Instant) {
    fun append(records: MutableList<JsonObject>, session: ResearchSession,
        access: ResearchAccess, operation: String, data: JsonObject): String {
        val id = UUID.randomUUID().toString()
        val unsigned = buildJsonObject {
            put("version", 1)
            put("id", id)
            put("operation", operation)
            put("recordedAt", now().toString())
            put("personId", session.person.value)
            put("unitId", session.unit.value)
            put("providerId", session.providerId)
            put("authorityId", access.authority.value)
            put("context", buildJsonObject {
                put("domain", session.context.domain)
                put("mission", session.context.mission?.value?.let(::JsonPrimitive) ?: JsonNull)
                put("description", session.context.description)
            })
            put("rightsPolicies", JsonArray(session.policies.sorted().map(::JsonPrimitive)))
            put("purpose", access.purpose)
            put("previous", records.lastOrNull()?.field("hash") ?: "GENESIS")
            put("data", data)
        }
        records.add(JsonObject(unsigned + ("hash" to JsonPrimitive(digest(unsigned.toString())))))
        return id
    }

}
