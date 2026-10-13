package sotaos.agent.research

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PublicResponseTest : FunSpec({
    test("deadline cancels a body stalled after successful headers without waiting for server completion") {
        val headers = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            headers.countDown()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            HttpClient.newHttpClient().use { client ->
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/")).GET().build()
                val error = shouldThrow<IOException> { readPublicResponse(client, request, Duration.ofSeconds(2)) }
                headers.count shouldBe 0L
                (error.cause is TimeoutException) shouldBe true
                release.count shouldBe 1L
            }
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
})
