package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.application.services.MissionSnapshot
import sotaos.domain.agency.Mission
import sotaos.domain.shared.*

internal object MissionSnapshotJson {
    fun encode(value: Mission): String = PayloadJsonMapping.payload(MissionSnapshot.values(value))

    fun decode(value: String): Mission {
        val item = Json.parseToJsonElement(value).jsonObject
        require(item.getValue("snapshotVersion").jsonPrimitive.int == 1) { "Unknown Mission snapshot format." }
        return Mission(MissionId(item.getValue("id").jsonPrimitive.content),
            ValueJsonMapping.subject(item.getValue("ownerKind").jsonPrimitive.content,
                item.getValue("ownerId").jsonPrimitive.content),
            item.getValue("intent").jsonPrimitive.content,
            item.getValue("objectives").jsonArray.map { it.jsonPrimitive.content },
            item.getValue("participants").jsonArray.map { PersonId(it.jsonPrimitive.content) },
            Mode.valueOf(item.getValue("mode").jsonPrimitive.content),
            LifecycleState.valueOf(item.getValue("state").jsonPrimitive.content))
    }
}
