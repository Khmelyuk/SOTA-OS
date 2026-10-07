package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import sotaos.application.sync.PeerCheckpoint
import sotaos.sync.HttpsSyncTransport
import sotaos.sync.SyncHttpException
import sotaos.test.sync.generateTestKey
import sotaos.test.sync.testTlsContext
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class P09MaterialRotationTest : FunSpec({
    test("governed token rotation rejects old material and resumes via atomic file delivery without a new client") {
        val file = Files.createTempFile("p09-rotation-", ".token")
        val staged = file.resolveSibling(file.fileName.toString() + ".new")
        try {
            HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
                a.enroll(b)
                Files.writeString(file, b.enroll(a))
                val secrets = P09Secrets(mapOf("SOTA_P09_PEER_TOKEN_FILE" to file.toString()))
                val tls = testTlsContext()
                HttpClient.newBuilder().sslContext(tls).build().use { client ->
                    P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                        val endpoint = URI("https://localhost:${server.address.port}/p09")
                        val outbound = HttpsSyncTransport(mapOf(b.id to endpoint),
                            messages, client) { "Bearer ${secrets.peerToken()}" }
                        a.record("before-rotation")
                        a.runtime.synchronize(b.id, outbound) shouldBe PeerCheckpoint(1, 1)
                        val token = b.runtime.peerProvisioning.rotate(invocation, authorityId, a.id, 1)
                        a.record("after-rotation")
                        shouldThrow<SyncHttpException> { a.runtime.synchronize(b.id, outbound) }.statusCode shouldBe 401
                        a.journal.checkpoint(b.id) shouldBe PeerCheckpoint(1, 1)
                        Files.writeString(staged, token + "\n")
                        Files.move(staged, file, ATOMIC_MOVE, REPLACE_EXISTING)
                        a.runtime.synchronize(b.id, outbound) shouldBe PeerCheckpoint(2, 2)
                        b.runtime.peerProvisioning.revoke(invocation, authorityId, a.id, 2)
                        shouldThrow<SyncHttpException> { a.runtime.synchronize(b.id, outbound) }.statusCode shouldBe 401
                        b.journal.records().size shouldBe 2
                    }
                }
            } }
        } finally {
            Files.deleteIfExists(staged)
            Files.deleteIfExists(file)
        }
    }
    test("TLS certificate replacement requires updated client trust and preserves sync state across restart") {
        val directory = Files.createTempDirectory("p09-cert-rotation-")
        val old = directory.resolve("old.p12")
        val replacement = directory.resolve("new.p12")
        val password = "fixture-password".toCharArray()
        try {
            generateTestKey(old, password)
            generateTestKey(replacement, password)
            HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
                a.enroll(b)
                val token = b.enroll(a)
                a.record("before-tls-update")
                val server = P09HttpsServer(InetSocketAddress("localhost", 0), P09Tls.server(old, password), b.runtime)
                val port = server.address.port
                server.use {
                    HttpClient.newBuilder().sslContext(P09Tls.client(old, password)).build().use { client ->
                        a.runtime.synchronize(b.id, transport(server, b.id, token, client)) shouldBe
                            PeerCheckpoint(1, 1)
                    }
                }
                a.reopen()
                b.reopen()
                a.record("after-tls-update")
                P09HttpsServer(InetSocketAddress("localhost", port), P09Tls.server(replacement, password), b.runtime)
                    .use { renewed ->
                    HttpClient.newBuilder().sslContext(P09Tls.client(old, password)).build().use { client ->
                        shouldThrow<Exception> { a.runtime.synchronize(b.id, transport(renewed, b.id, token, client)) }
                    }
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint(1, 1)
                    HttpClient.newBuilder().sslContext(P09Tls.client(replacement, password)).build().use { client ->
                        a.runtime.synchronize(b.id, transport(renewed, b.id, token, client)) shouldBe
                            PeerCheckpoint(2, 2)
                    }
                    b.journal.records().size shouldBe 2
                }
            } }
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(old)
            Files.deleteIfExists(replacement)
            Files.deleteIfExists(directory)
        }
    }
})
