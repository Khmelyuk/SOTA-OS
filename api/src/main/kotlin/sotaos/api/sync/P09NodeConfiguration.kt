package sotaos.api.sync

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Versioned, non-secret local configuration. No interpolation, escapes or implicit CLI overrides. */
object P09NodeConfiguration {
    fun load(path: Path, mode: String): Map<String, String> {
        val allowed = fields(mode)
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "Node configuration exceeds 64 KiB." }
        val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val values = linkedMapOf<String, String>()
        text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Node configuration requires key=value lines." }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(key in allowed && key !in values && value.isNotEmpty()) {
                "Unknown, duplicate, empty or unsupported node configuration field."
            }
            values[key] = value
        }
        require(values.remove("version") == "1") { "Node configuration requires version=1." }
        require(required(mode).all(values::containsKey)) { "Node configuration is missing required fields." }
        values["metrics-format"]?.let {
            require(it == "json" && "status-interval-seconds" in values) {
                "JSON metrics require a status interval."
            }
        }
        validate(values)
        val base = path.toAbsolutePath().normalize().parent
        PATH_FIELDS.forEach { key -> values[key]?.let { values[key] = base.resolve(it).normalize().toString() } }
        return values.toMap()
    }

    private fun required(mode: String): Set<String> = BASE_FIELDS + when (mode) {
        "serve" -> SERVER_FIELDS
        "once" -> CLIENT_FIELDS
        "run" -> SERVER_FIELDS + CLIENT_FIELDS
        else -> error("Use sync serve, sync once or sync run with --config.")
    }

    private fun fields(mode: String): Set<String> = required(mode) + "version" + when (mode) {
        "serve" -> setOf("bind")
        "run" -> setOf("bind", "interval-seconds", "max-backoff-seconds", "status-interval-seconds", "metrics-format")
        else -> emptySet()
    }

    private fun validate(values: Map<String, String>) {
        values["port"]?.let { require(it.toLongOrNull() in 1L..MAX_PORT) { "Invalid configured port." } }
        TIMER_FIELDS.forEach { field -> values[field]?.let {
            require(it.toLongOrNull() in 1L..MAX_SECONDS) { "Configured intervals must be 1 to 3600 seconds." }
        } }
        values["peer"]?.let { require(it != values["node"]) { "A node cannot synchronize with itself." } }
        values["endpoint"]?.let {
            val endpoint = runCatching { URI(it) }.getOrNull()
            require(endpoint != null && endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
                endpoint.userInfo == null && endpoint.fragment == null) {
                "Configured endpoint must be HTTPS without embedded credentials or fragment."
            }
        }
    }

    private val BASE_FIELDS = setOf("db", "node", "actor", "governance-context")
    private val SERVER_FIELDS = setOf("keystore", "port")
    private val CLIENT_FIELDS = setOf("peer", "endpoint", "truststore")
    private val PATH_FIELDS = setOf("db", "keystore", "truststore")
    private val TIMER_FIELDS = setOf("interval-seconds", "max-backoff-seconds", "status-interval-seconds")
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_PORT = 65535L
    private const val MAX_SECONDS = 3600L
}
