package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.application.ports.PeerTrustPolicy
import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.SotaId
import sotaos.domain.sync.*

internal object PeerPolicyJson {
    fun encode(policy: PeerTrustPolicy): String = buildJsonObject {
        put("peer", policy.peer.value)
        put("assertionEntities", JsonArray(policy.assertionEntities.map {
            buildJsonObject { put("kind", it.kind.name); put("id", it.id) }
        }))
        put("actors", JsonArray(policy.actors.map { actor ->
            val (kind, id) = ValueJsonMapping.subject(actor)
            buildJsonObject { put("kind", kind); put("id", id) }
        }))
        put("contexts", JsonArray(policy.contexts.map { Json.parseToJsonElement(JsonMapping.context(it)) }))
        put("directions", JsonArray(policy.directions.map { JsonPrimitive(it.name) }))
        put("importOrigins", JsonArray(policy.importOrigins.map { JsonPrimitive(it.value) }))
        put("exportOrigins", JsonArray(policy.exportOrigins.map { JsonPrimitive(it.value) }))
    }.toString()

    fun decode(value: String): PeerTrustPolicy {
        val obj = Json.parseToJsonElement(value).jsonObject
        return PeerTrustPolicy(
            SotaId(obj.getValue("peer").jsonPrimitive.content),
            obj.getValue("actors").jsonArray.map {
                ValueJsonMapping.subject(it.jsonObject.getValue("kind").jsonPrimitive.content,
                    it.jsonObject.getValue("id").jsonPrimitive.content)
            }.toSet(),
            obj.getValue("contexts").jsonArray.map { JsonMapping.context(it.toString()) }.toSet(),
            obj.getValue("directions").jsonArray.map { SyncDirection.valueOf(it.jsonPrimitive.content) }.toSet(),
            obj.getValue("importOrigins").jsonArray.map { SotaId(it.jsonPrimitive.content) }.toSet(),
            obj.getValue("exportOrigins").jsonArray.map { SotaId(it.jsonPrimitive.content) }.toSet(),
            obj.getValue("assertionEntities").jsonArray.map {
                SyncEntity(SyncEntityKind.valueOf(it.jsonObject.getValue("kind").jsonPrimitive.content),
                    it.jsonObject.getValue("id").jsonPrimitive.content)
            }.toSet()
        )
    }
}
