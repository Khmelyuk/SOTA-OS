package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09HttpsServer
import sotaos.api.sync.P09Tls
import sotaos.application.sync.*
import sotaos.sync.HttpsSyncTransport
import sotaos.test.sync.generateTestKey
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.KeyStore

class P09TlsTest : FunSpec({
    test("PKCS12 host accepts explicit trust and rejects unknown CA and mismatched hostname") {
        val directory = Files.createTempDirectory("p09-tls-loader-")
        val path = directory.resolve("server.p12")
        val password = "test-password".toCharArray()
        try {
            generateTestKey(path, password)
            ProductionFixture().use { f ->
                val token = f.enroll()
                val request = SyncRequest("tls", peerId, 0, SyncBatch(0, 1, listOf(f.record())))
                val host = P09HttpsServer(InetSocketAddress("localhost", 0), P09Tls.server(path, password), f.runtime)
                host.use { server ->
                    HttpClient.newBuilder().sslContext(P09Tls.client(path, password)).build().use { client ->
                        val wrongName = HttpsSyncTransport(mapOf(localId to
                            URI("https://127.0.0.1:${server.address.port}/p09")), messages, client) { "Bearer $token" }
                        shouldThrow<Exception> { wrongName.exchange(localId, request) }
                        transport(server, localId, token, client).exchange(localId, request).acceptedThrough shouldBe 1
                    }
                    HttpClient.newHttpClient().use { client ->
                        shouldThrow<Exception> { transport(server, localId, token, client).exchange(localId, request) }
                    }
                }
            }
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
    test("missing private key empty trust and wrong PKCS12 password fail before listening") {
        val directory = Files.createTempDirectory("p09-tls-empty-")
        val path = directory.resolve("empty.p12")
        val password = "test-password".toCharArray()
        try {
            val empty = KeyStore.getInstance("PKCS12").also { it.load(null, password) }
            Files.newOutputStream(path).use { empty.store(it, password) }
            shouldThrow<IllegalArgumentException> { P09Tls.server(path, password) }
            shouldThrow<IllegalArgumentException> { P09Tls.client(path, password) }
            shouldThrow<Exception> { P09Tls.server(path, "wrong-password".toCharArray()) }
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
})
