package sotaos.test.migration

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.api.sync.P09Runtime
import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightStore
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.LocalSyncIdentity
import java.time.Instant
import java.util.UUID

internal val historyPeer = SotaId("remote")
internal val historyActor = SubjectRef.Person(PersonId("remote-author"))
internal val historyClock: Instant = Instant.parse("2026-11-24T00:00:00Z")

internal fun HistoricalFixture.signedRecord(id: String): SyncRecord {
    val vectors = resource("vectors.json", "abd9b87029f311f7cb448f024fe7257c9e147c5dd22b41d02ac7004f926ca25e")
    check(vectors.getValue("fixtureOnly").jsonPrimitive.boolean)
    return vectors.getValue("records").jsonArray.map { JsonSyncRecordCodec().decode(it.jsonPrimitive.content) }
        .single { it.event.id.value == id }
}

internal fun historyRuntime(store: SqlDelightStore) = P09Runtime(store,
    LocalSyncIdentity(SotaId("local"), setOf(SubjectRef.Person(PersonId("local-author")))),
    Context("peer-governance"), Clock { historyClock }, IdGenerator { UUID.randomUUID().toString() })

internal fun P09Runtime.receive(record: SyncRecord, from: Long = 0): SyncResponse {
    val codec = JsonSyncMessageCodec()
    val request = SyncRequest("historical-migration", historyPeer, 0, SyncBatch(from, from + 1, listOf(record)))
    return codec.decodeResponse(exchange("Bearer public-historical-peer-token", codec.encodeRequest(request)))
}

internal fun HistoricalFixture.assertPreserved(before: Map<String, HistoricalRows>) = sql { connection ->
    before.forEach { (table, image) ->
        connection.rows(table, image.columns) shouldBe image.rows
    }
}
