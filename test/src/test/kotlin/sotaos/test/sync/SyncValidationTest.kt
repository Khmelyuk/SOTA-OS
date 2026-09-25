package sotaos.test.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.sync.HttpsSyncTransport
import java.net.URI

class SyncValidationTest : FunSpec({
    test("same event ID with changed content or origin is rejected atomically") {
        withNodes { a, b ->
            val existing = record("existing", a.id)
            a.service.recordLocal(existing)
            val altered = existing.copy(origin = b.id)
            val request = SyncRequest("request", b.id, 0, SyncBatch(0, 2,
                listOf(record("new", b.id), altered)))
            shouldThrow<IllegalArgumentException> { a.service.receive(b.id, request) }
            a.repository.records() shouldBe listOf(existing)
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
        }
    }

    test("missing parents and causal cycles reject the whole batch without advancing cursors") {
        withNodes { a, b ->
            val missing = record("child", b.id, parents = setOf(EventId("missing")))
            shouldThrow<IllegalArgumentException> {
                a.service.receive(b.id, SyncRequest("missing", b.id, 0, SyncBatch(0, 1, listOf(missing))))
            }
            val first = record("first", b.id, parents = setOf(EventId("second")))
            val second = record("second", b.id, parents = setOf(first.event.id))
            shouldThrow<IllegalArgumentException> {
                a.service.receive(b.id, SyncRequest("cycle", b.id, 0, SyncBatch(0, 2, listOf(first, second))))
            }
            a.repository.records() shouldBe emptyList()
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
        }
    }

    test("out-of-order parents within a batch are ordered before append") {
        withNodes { a, b ->
            val parent = record("parent", b.id)
            val child = record("child", b.id, parents = setOf(parent.event.id))
            a.service.receive(b.id, SyncRequest("ordered", b.id, 0, SyncBatch(0, 2, listOf(child, parent))))
            a.repository.records().map { it.event.id } shouldBe listOf(parent.event.id, child.event.id)
        }
    }

    test("a forged peer identity, journal gap or changed replay prefix cannot commit") {
        withNodes { a, b ->
            val request = SyncRequest("r", b.id, 0, SyncBatch(0, 1, listOf(record("b", b.id))))
            shouldThrow<IllegalArgumentException> { a.service.receive(SotaId("other"), request) }
            shouldThrow<IllegalArgumentException> {
                a.service.receive(b.id, request.copy(batch = SyncBatch(1, 2, request.batch.records)))
            }
            a.service.receive(b.id, request)
            shouldThrow<IllegalArgumentException> {
                a.service.receive(b.id, request.copy(batch = SyncBatch(0, 1, listOf(record("fake", b.id)))))
            }
            a.repository.records().size shouldBe 1
            a.repository.checkpoint(b.id).received shouldBe 1
        }
    }

    test("unexpected response correlation and acknowledgement leave checkpoints unchanged") {
        withNodes { a, b ->
            a.service.recordLocal(record("a", a.id))
            val invalid = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse =
                    SyncResponse("wrong", b.id, request.batch.through, SyncBatch(0, 0, emptyList()))
            }
            shouldThrow<IllegalArgumentException> { a.service.synchronize(b.id, invalid) }
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
        }
    }

    test("admission denial never records remote events") {
        withNodes { a, b ->
            TestSyncNode("a", a.path, SyncAdmission { _, _, _ -> error("untrusted") }).use { guarded ->
                shouldThrow<IllegalStateException> {
                    guarded.service.receive(b.id,
                        SyncRequest("r", b.id, 0, SyncBatch(0, 1, listOf(record("remote", b.id)))))
                }
                guarded.repository.records() shouldBe emptyList()
            }
        }
    }

    test("record codec retains nested payloads, provenance, signatures and correction references") {
        val original = record("event", SotaId("a"), claim("terms"), setOf(EventId("parent")))
        val detailed = original.copy(event = original.event.copy(
            payload = mapOf("nested" to mapOf("number" to 2L, "values" to listOf(true, null, "text"))),
            signature = "opaque-signature", correctsEventId = EventId("parent")))
        recordCodec.decode(recordCodec.encode(detailed)) shouldBe detailed
        val reordered = detailed.copy(event = detailed.event.copy(payload = detailed.event.payload.toSortedMap()))
        recordCodec.encode(reordered) shouldBe recordCodec.encode(detailed)
    }

    test("unsupported protocol versions, HTTP URLs and unknown peers are refused") {
        val request = SyncRequest("r", SotaId("a"), 0, SyncBatch(0, 0, emptyList()))
        val encoded = messageCodec.encodeRequest(request).replace("0.1", "future")
        shouldThrow<IllegalArgumentException> { messageCodec.decodeRequest(encoded) }
        shouldThrow<IllegalArgumentException> {
            HttpsSyncTransport(mapOf(SotaId("b") to URI("http://localhost/sync")), JsonSyncMessageCodec()) { "token" }
        }
        val transport = HttpsSyncTransport(emptyMap(), JsonSyncMessageCodec()) { "token" }
        shouldThrow<IllegalStateException> { transport.exchange(SotaId("unknown"), request) }
    }
})
