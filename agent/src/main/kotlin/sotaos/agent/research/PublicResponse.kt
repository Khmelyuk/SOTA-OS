package sotaos.agent.research

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounds the complete response, including a peer that sends headers but never finishes its body. */
internal fun readPublicResponse(client: HttpClient, request: HttpRequest, timeout: Duration): PublicHttpResponse {
    val pending = client.sendAsync(request) { BoundedBodySubscriber(MoltbookAdapter.MAX_RESPONSE) }
    return try {
        val response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        PublicHttpResponse(response.statusCode(), String(response.body(), Charsets.UTF_8))
    } catch (failure: TimeoutException) {
        throw IOException("Public response deadline exceeded.", failure)
    } catch (failure: ExecutionException) {
        throw IOException("Public response unavailable.", failure)
    } finally {
        if (!pending.isDone) pending.cancel(true)
    }
}
