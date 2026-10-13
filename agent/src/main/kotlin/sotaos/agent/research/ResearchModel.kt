package sotaos.agent.research

import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** External discussion is evidence, never an instruction or canonical SOTA knowledge. */
data class PublicDiscussion(
    val url: String,
    val externalId: String,
    val author: String,
    val publishedAt: Instant?,
    val title: String,
    val text: String
) {
    init {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(listOf(url, externalId, author, title).all { it.length in 1..MAX_FIELD })
        require(text.length in 1..MAX_TEXT)
    }

    internal fun payload(): JsonObject = buildJsonObject {
        put("url", url)
        put("externalId", externalId)
        put("author", author)
        put("publishedAt", publishedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("title", title)
        put("text", text)
        put("contentHash", digest(text))
        put("status", "KNOWLEDGE_CANDIDATE")
    }
}

data class DraftPreview(val id: String, val text: String, val contentHash: String)

enum class ArgumentKind { ARGUMENT, COUNTERARGUMENT }

internal const val MAX_FIELD = 2048
internal const val MAX_TEXT = 32000
internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun JsonObject.field(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.data(): JsonObject = getValue("data").jsonObject
