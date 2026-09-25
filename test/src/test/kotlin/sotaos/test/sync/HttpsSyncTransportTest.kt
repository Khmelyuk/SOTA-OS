package sotaos.test.sync

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.sync.PeerCheckpoint
import sotaos.sync.HttpsSyncTransport
import sotaos.sync.SyncEndpoint
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class HttpsSyncTransportTest : FunSpec({
    test("two SQLite nodes exchange JSON over authenticated localhost HTTPS") {
        withNodes { a, b ->
            val tls = testTlsContext()
            val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
            server.httpsConfigurator = HttpsConfigurator(tls)
            val endpoint = SyncEndpoint(b.service, messageCodec)
            server.createContext("/p09") { exchange ->
                exchange.use {
                    check(it.requestMethod == "POST")
                    check(it.requestHeaders.getFirst("Authorization") == "Bearer fixture-token")
                    val body = it.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val response = endpoint.exchange(a.id, body).toByteArray(Charsets.UTF_8)
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(HTTP_OK, response.size.toLong())
                    it.responseBody.write(response)
                }
            }
            server.start()
            try {
                val transport = HttpsSyncTransport(
                    mapOf(b.id to URI("https://localhost:${server.address.port}/p09")), messageCodec,
                    HttpClient.newBuilder().sslContext(tls).build()
                ) { "Bearer fixture-token" }
                a.service.recordLocal(record("https-a", a.id))
                b.service.recordLocal(record("https-b", b.id))
                a.service.synchronize(b.id, transport)
                a.repository.records().toSet() shouldBe b.repository.records().toSet()
                a.repository.records().size shouldBe 2
            } finally {
                server.stop(0)
            }
        }
    }

    test("HTTPS failure leaves the local checkpoint unchanged") {
        withNodes { a, b ->
            val tls = testTlsContext()
            val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
            server.httpsConfigurator = HttpsConfigurator(tls)
            server.createContext("/p09") { exchange -> exchange.use { it.sendResponseHeaders(UNAVAILABLE, -1) } }
            server.start()
            try {
                val transport = HttpsSyncTransport(
                    mapOf(b.id to URI("https://localhost:${server.address.port}/p09")), messageCodec,
                    HttpClient.newBuilder().sslContext(tls).build()
                ) { "Bearer fixture-token" }
                a.service.recordLocal(record("pending", a.id))
                shouldThrow<IllegalStateException> { a.service.synchronize(b.id, transport) }
                a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
                a.repository.records().size shouldBe 1
            } finally {
                server.stop(0)
            }
        }
    }
})

private const val HTTP_OK = 200
private const val UNAVAILABLE = 503

/** Ephemeral test key, trusted only by this test's client; hostname verification stays enabled. */
private fun testTlsContext(): SSLContext {
    val password = "fixture-password".toCharArray()
    val keyStore = temporaryKeyStore(password)
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keys.init(keyStore, password)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trust.init(keyStore)
    return SSLContext.getInstance("TLS").also { it.init(keys.keyManagers, trust.trustManagers, null) }
}

private fun temporaryKeyStore(password: CharArray): KeyStore {
    val directory = Files.createTempDirectory("sota-test-tls-")
    val path = directory.resolve("localhost.p12")
    try {
        generateTestKey(path, password)
        return KeyStore.getInstance("PKCS12").also { store ->
            Files.newInputStream(path).use { store.load(it, password) }
        }
    } finally {
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }
}

private fun generateTestKey(path: Path, password: CharArray) {
    val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
    val process = ProcessBuilder(
        keytool, "-genkeypair", "-alias", "sync-test", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
        "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", String(password), "-noprompt"
    ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        check(process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Test key generation timed out." }
        check(process.exitValue() == 0) { "Test key generation failed." }
    } finally {
        if (process.isAlive) process.destroyForcibly().waitFor()
    }
}

private const val KEYTOOL_TIMEOUT_SECONDS = 30L
