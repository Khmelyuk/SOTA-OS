package sotaos.sync

import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

/** HTTPS/REST adapter (ADR-005). Endpoint trust and credentials are explicit composition inputs. */
class HttpsSyncTransport(
    private val endpoints: Map<SotaId, URI>,
    private val codec: SyncMessageCodec,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build(),
    private val authorization: (SotaId) -> String
) : SyncTransport {
    init {
        require(client.followRedirects() == HttpClient.Redirect.NEVER) { "Peer credentials cannot follow redirects." }
        require(endpoints.values.all {
            it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null
        }) { "Sync endpoints must be explicit HTTPS URLs without embedded credentials." }
    }

    override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
        val endpoint = endpoints[peer] ?: error("No configured endpoint for this peer.")
        val credential = authorization(peer)
        require(credential.isNotBlank()) { "A peer credential is required." }
        val body = codec.encodeRequest(request)
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_SYNC_MESSAGE_BYTES)
        val httpRequest = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", credential)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build()
        val pending = client.sendAsync(httpRequest) { LimitedResponseBody(MAX_SYNC_MESSAGE_BYTES) }
        return try {
            // Bound the complete response, including a body that stalls after successful headers.
            val response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            check(response.statusCode() == HTTP_OK) { "Sync exchange failed: HTTP ${response.statusCode()}." }
            codec.decodeResponse(String(response.body(), StandardCharsets.UTF_8))
        } finally {
            if (!pending.isDone) pending.cancel(true)
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private val TIMEOUT = Duration.ofSeconds(15)
    }
}

const val MAX_SYNC_MESSAGE_BYTES = 4 * 1024 * 1024
