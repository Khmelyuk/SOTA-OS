package sotaos.test.rehearsal

import sotaos.api.sync.P09Tls
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Independent temporary certificates. Trust stores contain public certificates only. */
internal class RehearsalTls(directory: Path) : AutoCloseable {
    private val folder = Files.createTempDirectory(directory, "tls-")
    val password: String = UUID.randomUUID().toString()

    init {
        runCatching { listOf("a", "b").forEach(::generate) }.onFailure { close() }.getOrThrow()
    }

    fun keyStore(name: String): Path = folder.resolve("$name.p12")

    fun client(name: String): HttpClient {
        val secret = password.toCharArray()
        return try {
            HttpClient.newBuilder().sslContext(P09Tls.client(folder.resolve("$name-trust.p12"), secret))
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun generate(name: String) {
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "rehearsal", "-keyalg", "EC", "-groupname", "secp256r1",
            "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
            "-keystore", keyStore(name).toString(), "-storepass:env", "SOTA_REHEARSAL_TLS_PASSWORD", "-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["SOTA_REHEARSAL_TLS_PASSWORD"] = password }.start()
        try {
            check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Rehearsal TLS generation failed."
            }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
        val secret = password.toCharArray()
        try {
            val keys = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keyStore(name)).use { keys.load(it, secret) }
            val trust = KeyStore.getInstance("PKCS12")
            trust.load(null, secret)
            trust.setCertificateEntry("peer", keys.getCertificate("rehearsal"))
            Files.newOutputStream(folder.resolve("$name-trust.p12")).use { trust.store(it, secret) }
        } finally {
            secret.fill('\u0000')
        }
    }

    override fun close() {
        Files.list(folder).use { files -> files.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(folder)
    }
}

private const val KEYTOOL_SECONDS = 90L
