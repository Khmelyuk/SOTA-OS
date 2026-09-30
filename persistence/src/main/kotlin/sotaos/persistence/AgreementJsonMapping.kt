package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.domain.relation.Agreement
import sotaos.domain.shared.LifecycleState

internal object AgreementJsonMapping {
    fun encode(value: Agreement): String = buildJsonObject {
        put("parties", JsonArray(value.parties.map { party ->
            val (kind, id) = ValueJsonMapping.subject(party)
            buildJsonObject { put("kind", kind); put("id", id) }
        }))
        put("purpose", value.purpose)
        put("terms", Json.parseToJsonElement(ValueJsonMapping.strings(value.terms)))
        put("validity", Json.parseToJsonElement(ValueJsonMapping.validity(value.validity.from, value.validity.until)))
        put("exitTerms", value.exitTerms)
        put("state", value.state.name)
    }.toString()

    fun decode(value: String): Agreement {
        val item = Json.parseToJsonElement(value).jsonObject
        return Agreement(item.getValue("parties").jsonArray.map {
            ValueJsonMapping.subject(it.jsonObject.getValue("kind").jsonPrimitive.content,
                it.jsonObject.getValue("id").jsonPrimitive.content)
        }, item.getValue("purpose").jsonPrimitive.content,
            ValueJsonMapping.strings(item.getValue("terms").toString()),
            ValueJsonMapping.validity(item.getValue("validity").toString()),
            item.getValue("exitTerms").jsonPrimitive.content,
            LifecycleState.valueOf(item.getValue("state").jsonPrimitive.content))
    }
}
