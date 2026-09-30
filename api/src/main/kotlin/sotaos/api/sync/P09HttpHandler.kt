package sotaos.api.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import sotaos.sync.MAX_SYNC_MESSAGE_BYTES
import sotaos.sync.PeerAuthenticationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Error responses never contain request data, credentials, database details or exception messages. */
internal class P09HttpHandler(
    private val runtime: P09Runtime,
    private val deadlines: ScheduledExecutorService,
    private val timeout: Duration
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val deadline = deadlines.schedule({ exchange.close() }, timeout.toMillis(), TimeUnit.MILLISECONDS)
        try {
            exchange.use { process(it) }
        } catch (_: IOException) {
            // The peer disconnected or the body deadline closed the connection; retry uses durable cursors.
            exchange.close()
        } finally {
            deadline.cancel(false)
        }
    }

    private fun process(exchange: HttpExchange) {
        val failure = validateHeaders(exchange)
        if (failure != null) {
            respond(exchange, failure, "Request rejected")
        } else {
            val result = try {
                val bytes = exchange.requestBody.readNBytes(MAX_SYNC_MESSAGE_BYTES + 1)
                if (bytes.size > MAX_SYNC_MESSAGE_BYTES) {
                    PAYLOAD_TOO_LARGE to "Request rejected"
                } else {
                    val body = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                    OK to runtime.exchange(exchange.requestHeaders.getFirst("Authorization"), body)
                }
            } catch (_: PeerAuthenticationException) {
                UNAUTHORIZED to "Request rejected"
            } catch (_: IllegalArgumentException) {
                BAD_REQUEST to "Request rejected"
            } catch (_: java.nio.charset.CharacterCodingException) {
                BAD_REQUEST to "Request rejected"
            } catch (failure: IOException) {
                throw failure
            } catch (_: Exception) {
                INTERNAL_ERROR to "Exchange failed"
            }
            respond(exchange, result.first, result.second)
        }
    }

    private fun validateHeaders(exchange: HttpExchange): Int? {
        val contentType = exchange.requestHeaders["Content-Type"]?.singleOrNull()?.lowercase()
        val length = exchange.requestHeaders["Content-Length"]?.singleOrNull()?.toLongOrNull()
        return when {
            exchange.requestURI.rawPath != "/p09" || exchange.requestURI.rawQuery != null -> NOT_FOUND
            exchange.requestMethod != "POST" -> METHOD_NOT_ALLOWED
            exchange.requestHeaders["Authorization"]?.singleOrNull().isNullOrBlank() -> UNAUTHORIZED
            contentType !in setOf("application/json", "application/json; charset=utf-8") -> UNSUPPORTED_MEDIA
            exchange.requestHeaders.containsKey("Content-Encoding") -> UNSUPPORTED_MEDIA
            length != null && length > MAX_SYNC_MESSAGE_BYTES -> PAYLOAD_TOO_LARGE
            else -> null
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(UTF_8)
        val contentType = if (status == OK) "application/json" else "text/plain; charset=utf-8"
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Connection", "close")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
        const val PAYLOAD_TOO_LARGE = 413
        const val UNSUPPORTED_MEDIA = 415
        const val INTERNAL_ERROR = 500
    }
}
