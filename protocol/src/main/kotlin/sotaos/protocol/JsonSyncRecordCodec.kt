package sotaos.protocol

import kotlinx.serialization.json.*
import sotaos.application.sync.SyncRecordCodec
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.*
import java.time.Instant

/** Fixed field order and sorted maps make replay comparison independent of JSON key order. */
class JsonSyncRecordCodec : SyncRecordCodec {
    override fun encode(record: SyncRecord): String = buildJsonObject {
        put("version", record.version)
        put("origin", record.origin.value)
        put("parents", JsonArray(record.parents.map { it.value }.sorted().map(::JsonPrimitive)))
        put("event", encodeEvent(record.event))
        put("assertion", record.assertion?.let(::encodeAssertion) ?: JsonNull)
    }.toString()

    override fun decode(encoded: String): SyncRecord {
        val obj = Json.parseToJsonElement(encoded).jsonObject
        return SyncRecord(
            decodeEvent(obj.getValue("event").jsonObject), SotaId(obj.text("origin")),
            obj.getValue("parents").jsonArray.map { EventId(it.jsonPrimitive.content) }.toSet(),
            obj.getValue("assertion").takeUnless { it == JsonNull }?.jsonObject?.let(::decodeAssertion),
            obj.getValue("version").jsonPrimitive.int
        )
    }

    private fun encodeEvent(event: Event): JsonObject = buildJsonObject {
        put("id", event.id.value); put("type", event.type); put("actor", SyncJsonValues.subject(event.actor))
        put("timestamp", event.timestamp.toString()); put("context", SyncJsonValues.context(event.context))
        put("authority_ref", event.authorityRef); put("decision_ref", event.decisionRef)
        put("payload", SyncJsonValues.payload(event.payload)); put("result_ref", event.resultRef)
        put("provenance", SyncJsonValues.provenance(event.provenance))
        put("content_hash", event.contentHash); put("signature", event.signature)
        put("corrects_event_id", event.correctsEventId?.value)
    }

    private fun decodeEvent(obj: JsonObject): Event = Event(
        id = EventId(obj.text("id")), type = obj.text("type"),
        actor = SyncJsonValues.subject(obj.getValue("actor").jsonObject),
        timestamp = Instant.parse(obj.text("timestamp")),
        context = SyncJsonValues.context(obj.getValue("context").jsonObject),
        authorityRef = obj.optional("authority_ref"), decisionRef = obj.optional("decision_ref"),
        payload = obj.getValue("payload").jsonObject.mapValues { SyncJsonValues.payload(it.value) },
        resultRef = obj.optional("result_ref"),
        provenance = SyncJsonValues.provenance(obj.getValue("provenance").jsonObject),
        contentHash = obj.text("content_hash"), signature = obj.optional("signature"),
        correctsEventId = obj.optional("corrects_event_id")?.let(::EventId)
    )

    private fun encodeAssertion(assertion: StateAssertion): JsonObject = buildJsonObject {
        put("kind", assertion.entity.kind.name); put("id", assertion.entity.id)
        put("context", SyncJsonValues.context(assertion.context))
        put("from", assertion.validity.from.toString()); put("until", assertion.validity.until?.toString())
        put("attributes", JsonObject(assertion.attributes.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }

    private fun decodeAssertion(obj: JsonObject): StateAssertion = StateAssertion(
        SyncEntity(SyncEntityKind.valueOf(obj.text("kind")), obj.text("id")),
        SyncJsonValues.context(obj.getValue("context").jsonObject),
        Validity(Instant.parse(obj.text("from")), obj.optional("until")?.let(Instant::parse)),
        obj.getValue("attributes").jsonObject.mapValues { it.value.jsonPrimitive.content }
    )
}
