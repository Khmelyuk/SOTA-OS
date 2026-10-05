package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.api.cli.formatNodeMetrics
import sotaos.api.sync.*

class NodeMetricsJsonTest : FunSpec({
    test("empty metrics are valid versioned JSON with numeric fields and no fabricated responses") {
        val value = Json.parseToJsonElement(formatNodeMetrics(InboundMetrics(), emptyList())).jsonObject
        value.getValue("version").jsonPrimitive.int shouldBe 1
        val inbound = value.getValue("inbound").jsonObject
        inbound.getValue("inFlight").jsonPrimitive.long shouldBe 0L
        inbound.getValue("responses").jsonObject shouldBe emptyMap()
        val outbound = value.getValue("outbound").jsonObject
        outbound.getValue("configuredPeers").jsonPrimitive.int shouldBe 0
    }
    test("JSON aggregates outbound counters and phases without exposing identity or arbitrary labels") {
        val peers = listOf(
            PeerSyncStatus(attempts = 2, phase = SyncPhase.RUNNING,
                metrics = SyncMetrics(failures = 1, failuresByCategory = mapOf(SyncFailure.NETWORK to 1L))),
            PeerSyncStatus(attempts = 1, phase = SyncPhase.WAITING, metrics = SyncMetrics(successes = 1)))
        val inbound = InboundMetrics(started = 3, completed = 2, aborted = 1, responses = mapOf(401 to 1L))
        val value = Json.parseToJsonElement(formatNodeMetrics(inbound, peers)).jsonObject
        val incoming = value.getValue("inbound").jsonObject
        incoming.getValue("inFlight").jsonPrimitive.long shouldBe 1L
        incoming.getValue("responses").jsonObject.getValue("401").jsonPrimitive.long shouldBe 1L
        val outgoing = value.getValue("outbound").jsonObject
        outgoing.getValue("attempts").jsonPrimitive.long shouldBe 3L
        outgoing.getValue("successes").jsonPrimitive.long shouldBe 1L
        outgoing.getValue("failures").jsonPrimitive.long shouldBe 1L
        outgoing.getValue("phases").jsonObject.getValue("RUNNING").jsonPrimitive.long shouldBe 1L
        outgoing.getValue("failuresByCategory").jsonObject.keys shouldBe SyncFailure.entries.map { it.name }.toSet()
    }
})
