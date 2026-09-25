package sotaos.protocol

import kotlinx.serialization.json.*
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId

/** P09 delta envelope. The HTTP adapter supplies authenticated identity independently. */
class JsonSyncMessageCodec(private val records: SyncRecordCodec = JsonSyncRecordCodec()) : SyncMessageCodec {
    override fun encodeRequest(request: SyncRequest): String = buildJsonObject {
        put("protocol", "P09"); put("version", "0.1"); put("action", "exchange")
        put("message_id", request.id); put("sender", request.sender.value)
        put("received_through", request.receivedThrough); put("payload", batch(request.batch))
    }.toString()

    override fun decodeRequest(encoded: String): SyncRequest {
        val obj = envelope(encoded, "exchange")
        return SyncRequest(obj.text("message_id"), SotaId(obj.text("sender")),
            obj.getValue("received_through").jsonPrimitive.long, batch(obj.getValue("payload").jsonObject))
    }

    override fun encodeResponse(response: SyncResponse): String = buildJsonObject {
        put("protocol", "P09"); put("version", "0.1"); put("action", "delta")
        put("correlation_id", response.requestId); put("sender", response.sender.value)
        put("accepted_through", response.acceptedThrough); put("payload", batch(response.batch))
    }.toString()

    override fun decodeResponse(encoded: String): SyncResponse {
        val obj = envelope(encoded, "delta")
        return SyncResponse(obj.text("correlation_id"), SotaId(obj.text("sender")),
            obj.getValue("accepted_through").jsonPrimitive.long, batch(obj.getValue("payload").jsonObject))
    }

    private fun batch(batch: SyncBatch): JsonObject = buildJsonObject {
        put("after", batch.after); put("through", batch.through)
        put("records", JsonArray(batch.records.map { Json.parseToJsonElement(records.encode(it)) }))
    }

    private fun batch(obj: JsonObject): SyncBatch = SyncBatch(
        obj.getValue("after").jsonPrimitive.long, obj.getValue("through").jsonPrimitive.long,
        obj.getValue("records").jsonArray.map { records.decode(it.toString()) }
    )

    private fun envelope(encoded: String, action: String): JsonObject {
        val obj = Json.parseToJsonElement(encoded).jsonObject
        require(obj.text("protocol") == "P09" && obj.text("version") == "0.1" && obj.text("action") == action) {
            "Unsupported P09 envelope."
        }
        return obj
    }
}
