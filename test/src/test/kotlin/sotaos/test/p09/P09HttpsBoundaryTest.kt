package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09HttpsServer
import sotaos.application.sync.*
import sotaos.sync.MAX_SYNC_MESSAGE_BYTES
import sotaos.test.sync.testTlsContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLSocket

class P09HttpsBoundaryTest : FunSpec({
    test("HTTP boundary rejects wrong routes methods media credentials and tampered signatures without writes") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, f.runtime).use { server ->
                    val request = SyncRequest("http", peerId, 0, SyncBatch(0, 1, listOf(f.record())))
                    val valid = messages.encodeRequest(request)
                    fun send(body: String, path: String = "/p09", method: String = "POST",
                        credential: String = "Bearer $token", media: String = "application/json"): Int = client.send(
                        HttpRequest.newBuilder(URI("https://localhost:${server.address.port}$path"))
                            .header("Authorization", credential).header("Content-Type", media)
                            .method(method, HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(5))
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode()
                    send(valid, path = "/p09/extra") shouldBe 404
                    send(valid, method = "PUT") shouldBe 405
                    send(valid, media = "text/plain") shouldBe 415
                    send("not JSON", credential = "Bearer unknown") shouldBe 401
                    send("not JSON") shouldBe 400
                    send(valid.replace("original", "tampered")) shouldBe 400
                    f.repositories.events.findById(f.record().event.id) shouldBe null
                    waitForSync { server.inboundMetrics().completed == 6L }
                    val before = server.inboundMetrics()
                    send(valid) shouldBe 200
                    waitForSync { server.inboundMetrics().completed == 7L }
                    val metrics = server.inboundMetrics()
                    metrics.started shouldBe 7L
                    metrics.inFlight shouldBe 0L
                    metrics.aborted shouldBe 0L
                    metrics.responses shouldBe mapOf(404 to 1L, 405 to 1L, 415 to 1L, 401 to 1L, 400 to 2L, 200 to 1L)
                    (metrics.totalDurationNanos > 0) shouldBe true
                    before.completed shouldBe 6L
                }
            }
        }
    }
    test("both declared and chunked oversized bodies are rejected before journal mutation") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, f.runtime).use { server ->
                    val bytes = ByteArray(MAX_SYNC_MESSAGE_BYTES + 1) { ' '.code.toByte() }
                    (tls.socketFactory.createSocket("localhost", server.address.port) as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        socket.startHandshake()
                        val headers = "POST /p09 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\n" +
                            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                        socket.outputStream.write(headers.toByteArray(Charsets.UTF_8))
                        socket.outputStream.flush()
                        socket.inputStream.bufferedReader().readLine().split(" ")[1] shouldBe "413"
                    }
                    listOf(HttpRequest.BodyPublishers.ofByteArray(bytes),
                        HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }).forEach { body ->
                        val request = HttpRequest.newBuilder(URI("https://localhost:${server.address.port}/p09"))
                            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5)).POST(body).build()
                        try {
                            client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 413
                        } catch (failure: IOException) {
                            // Early rejection may reset an upload before HttpClient exposes response headers.
                            (failure is HttpTimeoutException) shouldBe false
                        }
                    }
                    f.repositories.events.findById(f.record().event.id) shouldBe null
                    val request = SyncRequest("after-large", peerId, 0, SyncBatch(0, 1, listOf(f.record())))
                    transport(server, localId, token, client).exchange(localId, request).acceptedThrough shouldBe 1
                }
            }
        }
    }
    test("stalled body deadline releases the server for the next exchange") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val tls = testTlsContext()
            P09HttpsServer(InetSocketAddress("localhost", 0), tls, f.runtime,
                Duration.ofMillis(500)).use { server ->
                (tls.socketFactory.createSocket("localhost", server.address.port) as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    val headers = "POST /p09 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\n" +
                        "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{"
                    socket.outputStream.write(headers.toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                    socket.inputStream.read() shouldBe -1
                }
                waitForSync { server.inboundMetrics().completed == 1L }
                server.inboundMetrics().aborted shouldBe 1L
                server.inboundMetrics().responses shouldBe emptyMap()
                HttpClient.newBuilder().sslContext(tls).build().use { client ->
                    val request = SyncRequest("after-stall", peerId, 0, SyncBatch(0, 1, listOf(f.record())))
                    transport(server, localId, token, client).exchange(localId, request).acceptedThrough shouldBe 1
                }
            }
        }
    }
})
