package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09HttpsServer
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.sync.HttpsSyncTransport
import sotaos.test.sync.testTlsContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient

class HttpsProductionPilotTest : FunSpec({
    test("signed HTTPS exchange recovers a lost acknowledgement after both SQLite nodes restart") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            val tokenForA = a.enroll(b)
            val tokenForB = b.enroll(a)
            a.record("offline-a")
            b.record("offline-b")
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    val https = transport(server, b.id, tokenForB, client)
                    val loseResponse = object : SyncTransport {
                        override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                            https.exchange(peer, request)
                            throw IOException("response lost after remote commit")
                        }
                    }
                    shouldThrow<IOException> { a.runtime.synchronize(b.id, loseResponse) }
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
                    b.journal.records().size shouldBe 2
                }
                a.reopen()
                b.reopen()
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    val https = transport(server, b.id, tokenForB, client)
                    a.runtime.synchronize(b.id, https) shouldBe PeerCheckpoint(1, 2)
                    a.runtime.synchronize(b.id, https) shouldBe PeerCheckpoint(2, 2)
                    a.runtime.synchronize(b.id, https) shouldBe PeerCheckpoint(2, 2)
                    a.journal.records().toSet() shouldBe b.journal.records().toSet()
                }
                a.record("after-restart")
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, a.runtime).use { server ->
                    val https = transport(server, a.id, tokenForA, client)
                    b.runtime.synchronize(a.id, https)
                    b.journal.records().toSet() shouldBe a.journal.records().toSet()
                    b.journal.records().size shouldBe 3
                }
            }
        } }
    }
    test("HTTPS host reuses durable credential rotation and revocation after restart") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val oldToken = b.enroll(a)
            val replacement = b.runtime.peerProvisioning.rotate(invocation, authorityId, a.id, 1)
            b.reopen()
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    shouldThrow<IllegalStateException> {
                        a.runtime.synchronize(b.id, transport(server, b.id, oldToken, client))
                    }
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
                    a.runtime.synchronize(b.id, transport(server, b.id, replacement, client)) shouldBe PeerCheckpoint()
                }
                b.runtime.peerProvisioning.revoke(invocation, authorityId, a.id, 2)
                b.reopen()
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    shouldThrow<IllegalStateException> {
                        a.runtime.synchronize(b.id, transport(server, b.id, replacement, client))
                    }
                }
            }
        } }
    }
})

internal fun transport(server: P09HttpsServer, peer: SotaId, token: String, client: HttpClient) =
    HttpsSyncTransport(mapOf(peer to URI("https://localhost:${server.address.port}/p09")), messages, client) {
        "Bearer $token"
    }
