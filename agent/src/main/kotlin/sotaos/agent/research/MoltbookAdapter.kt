package sotaos.agent.research

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant

/** Explicit statuses, without response bodies, tokens, proxy retries or registration. */
enum class FetchStatus { OK, GEO_BLOCKED, FORBIDDEN, UNAVAILABLE, INVALID_RESPONSE }
data class DiscussionFetch(val status: FetchStatus, val discussions: List<PublicDiscussion> = emptyList())
data class PublicHttpResponse(val status: Int, val body: String)
fun interface PublicDiscussionTransport {
    fun get(): PublicHttpResponse
}

/** One fixed public GET endpoint. Inject transport only from trusted local composition/test code. */
class MoltbookAdapter(private val transport: PublicDiscussionTransport = MoltbookPublicTransport()) {
    fun fetch(): DiscussionFetch = try {
        val response = transport.get()
        require(response.body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE)
        when (response.status) {
            FORBIDDEN -> blocked(response.body)
            OK -> parse(response.body)
            else -> DiscussionFetch(FetchStatus.UNAVAILABLE)
        }
    } catch (_: IllegalArgumentException) {
        DiscussionFetch(FetchStatus.INVALID_RESPONSE)
    } catch (_: NoSuchElementException) {
        DiscussionFetch(FetchStatus.INVALID_RESPONSE)
    } catch (_: java.time.DateTimeException) {
        DiscussionFetch(FetchStatus.INVALID_RESPONSE)
    } catch (_: java.io.IOException) {
        DiscussionFetch(FetchStatus.UNAVAILABLE)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        DiscussionFetch(FetchStatus.UNAVAILABLE)
    }

    private fun blocked(body: String): DiscussionFetch {
        val error = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }
            .getOrNull()
        return DiscussionFetch(if (error == "geo_blocked") FetchStatus.GEO_BLOCKED else FetchStatus.FORBIDDEN)
    }

    private fun parse(body: String): DiscussionFetch {
        val root = Json.parseToJsonElement(body).jsonObject
        val posts = requireNotNull(root["posts"]).jsonArray
        require(posts.size <= MAX_POSTS)
        val discussions = posts.map { element ->
            val post = element.jsonObject
            val id = post.textField("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val published = post["created_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
            PublicDiscussion("https://www.moltbook.com/post/$id", id,
                post.getValue("author").jsonObject.textField("name"), published,
                post.textField("title"), post.textField("content"))
        }
        return DiscussionFetch(FetchStatus.OK, discussions)
    }

    private fun JsonObject.textField(name: String): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString) { "Public discussion fields must be strings." }
        return value.content
    }

    companion object {
        internal const val MAX_RESPONSE = 1024 * 1024
        private const val MAX_POSTS = 20
        private const val OK = 200
        private const val FORBIDDEN = 403
    }
}

class MoltbookPublicTransport : PublicDiscussionTransport {
    override fun get(): PublicHttpResponse {
        val timeout = Duration.ofSeconds(TIMEOUT_SECONDS)
        val request = HttpRequest.newBuilder(URI("https://www.moltbook.com/api/v1/posts?limit=20"))
            .timeout(timeout).header("Accept", "application/json").GET().build()
        return HttpClient.newBuilder().connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER).build().use { client ->
                readPublicResponse(client, request, timeout)
            }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
    }
}
