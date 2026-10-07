package sotaos.api.sync

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** Secret paths are trusted local input; file-backed bearer tokens are reread on each exchange. */
class P09Secrets(environment: Map<String, String> = System.getenv()) {
    private val environment = environment.toMap()

    fun peerToken(): String = read("SOTA_P09_PEER_TOKEN").also {
        require(it.isNotBlank() && it.none(Char::isWhitespace)) { "Invalid peer credential format." }
    }

    fun tlsPassword(): CharArray = read("SOTA_P09_TLS_PASSWORD").toCharArray()

    private fun read(name: String): String {
        val direct = environment[name]
        val file = environment["${name}_FILE"]
        require((direct == null) != (file == null)) { "Set exactly one of $name or ${name}_FILE." }
        val value = if (file == null) requireNotNull(direct) else fromFile(file)
        require(value.isNotEmpty() && value.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "A configured secret must be a single nonempty line."
        }
        return value
    }

    private fun fromFile(file: String): String {
        require(file.isNotBlank()) { "A secret file path is required." }
        return try {
            val path = Path.of(file)
            require(Files.isRegularFile(path)) { "A configured secret source must be a regular file." }
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_SECRET_BYTES + 1) }
            try {
                require(bytes.size <= MAX_SECRET_BYTES) { "Configured secret file exceeds the size limit." }
                val value = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                if (value.endsWith("\n")) value.removeSuffix("\n").removeSuffix("\r") else value
            } finally { bytes.fill(0) }
        } catch (_: IOException) {
            throw IllegalArgumentException("Configured secret file cannot be read as UTF-8.")
        }
    }

    private companion object { const val MAX_SECRET_BYTES = 4096 }
}
