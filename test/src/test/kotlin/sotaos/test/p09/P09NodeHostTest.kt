package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.test.sync.testTlsContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

class P09NodeHostTest : FunSpec({
    test("two running HTTPS hosts converge while both receive and initiate background exchanges") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            val tokenForA = a.enroll(b)
            val tokenForB = b.enroll(a)
            a.record("a-first")
            b.record("b-first")
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                val outboundA = RestartableHttpsTransport(b.id, tokenForB, client)
                val outboundB = RestartableHttpsTransport(a.id, tokenForA, client)
                P09NodeHost(InetSocketAddress("localhost", 0), tls, a.runtime, setOf(b.id), outboundA,
                    quickSyncSettings()).use { hostA ->
                    P09NodeHost(InetSocketAddress("localhost", 0), tls, b.runtime, setOf(a.id), outboundB,
                        quickSyncSettings()).use { hostB ->
                        outboundA.endpoint.set(URI("https://localhost:${hostB.address.port}/p09"))
                        outboundB.endpoint.set(URI("https://localhost:${hostA.address.port}/p09"))
                        waitForSync {
                            hostA.snapshot()[b.id]?.lastCheckpoint == PeerCheckpoint(2, 2) &&
                                hostB.snapshot()[a.id]?.lastCheckpoint == PeerCheckpoint(2, 2)
                        }
                        // A different runtime on the same store shares the local-access gate.
                        a.record("a-while-running")
                        waitForSync {
                            hostA.snapshot()[b.id]?.lastCheckpoint == PeerCheckpoint(3, 3) &&
                                hostB.snapshot()[a.id]?.lastCheckpoint == PeerCheckpoint(3, 3)
                        }
                    }
                }
                a.journal.records().toSet() shouldBe b.journal.records().toSet()
                a.journal.records().size shouldBe 3
            }
        } }
    }
    test("background retry resumes durable checkpoints after a lost HTTPS acknowledgement and store restart") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val token = b.enroll(a)
            val record = a.record("before-crash")
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    val https = transport(server, b.id, token, client)
                    val lostReply = object : SyncTransport {
                        override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                            https.exchange(peer, request)
                            throw IOException("acknowledgement lost")
                        }
                    }
                    val pauseRetry = quickSyncSettings().copy(retryBase = Duration.ofSeconds(30),
                        maxBackoff = Duration.ofSeconds(30))
                    P09SyncLoop(a.runtime, setOf(b.id), lostReply, pauseRetry).use { loop ->
                        waitForSync { loop.snapshot()[b.id]?.consecutiveFailures == 1 }
                    }
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
                }
                a.reopen()
                b.reopen()
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    P09SyncLoop(a.runtime, setOf(b.id), transport(server, b.id, token, client),
                        quickSyncSettings()).use { loop ->
                        waitForSync { loop.snapshot()[b.id]?.lastCheckpoint == PeerCheckpoint(1, 1) }
                    }
                }
                b.journal.records() shouldBe listOf(record)
                a.journal.records() shouldBe listOf(record)
            }
        } }
    }
    test("background loop drains more than one bounded journal page") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val token = b.enroll(a)
            a.store.withLocalAccess { a.store.database.transaction {
                repeat(MAX_SYNC_BATCH + 1) { a.record("page-$it") }
            } }
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    P09SyncLoop(a.runtime, setOf(b.id), transport(server, b.id, token, client),
                        quickSyncSettings()).use { loop ->
                        val count = (MAX_SYNC_BATCH + 1).toLong()
                        waitForSync { loop.snapshot()[b.id]?.lastCheckpoint == PeerCheckpoint(count, count) }
                    }
                }
                b.journal.records().toSet() shouldBe a.journal.records().toSet()
                b.journal.records().size shouldBe MAX_SYNC_BATCH + 1
            }
        } }
    }
    test("offline peer recovers without restarting the loop and an untrusted peer cannot starve a valid one") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val token = b.enroll(a)
            a.record("offline-local")
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                val outbound = RestartableHttpsTransport(b.id, token, client)
                val unknown = SotaId("not-enrolled")
                P09SyncLoop(a.runtime, setOf(unknown, b.id), outbound, quickSyncSettings()).use { loop ->
                    waitForSync { (loop.snapshot()[b.id]?.consecutiveFailures ?: 0) >= 2 }
                    P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                        outbound.endpoint.set(URI("https://localhost:${server.address.port}/p09"))
                        waitForSync { loop.snapshot()[b.id]?.lastCheckpoint == PeerCheckpoint(1, 1) }
                        loop.snapshot()[b.id]?.consecutiveFailures shouldBe 0
                        ((loop.snapshot()[unknown]?.consecutiveFailures ?: 0) > 0) shouldBe true
                    }
                }
                b.journal.records().size shouldBe 1
            }
        } }
    }
})
