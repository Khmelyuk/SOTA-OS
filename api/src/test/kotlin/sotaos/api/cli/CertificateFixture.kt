package sotaos.api.cli

import sotaos.api.sync.P09Secrets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

internal class CertificateFixture(validityDays: Int = 90) : AutoCloseable {
    val directory: Path = Files.createTempDirectory("sota-certificate-check-")
    val server: Path = directory.resolve("server.p12")
    val trustOnly: Path = directory.resolve("trust-only.p12")
    val empty: Path = directory.resolve("empty.p12")
    val database: Path = directory.resolve("unopened.db")
    val password = "public-fixture-password".toCharArray()
    val unusualAlias = "extra\n\u001b"
    val certificate: X509Certificate
    val secrets = P09Secrets(mapOf("SOTA_P09_TLS_PASSWORD" to String(password),
        "SOTA_P09_PEER_TOKEN" to "public-fixture-peer-token"))

    init {
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-validity", validityDays.toString(),
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
            "-keystore", server.toString(), "-storepass", String(password), "-noprompt")
        val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS)) { "Test key generation timed out" }
            check(process.exitValue() == 0) { "Test key generation failed" }
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(server).use { store.load(it, password) }
        certificate = store.getCertificate("server") as X509Certificate
        store.setKeyEntry(unusualAlias, store.getKey("server", password), password, store.getCertificateChain("server"))
        store.setCertificateEntry("trusted-only", certificate)
        Files.newOutputStream(server).use { store.store(it, password) }
        val trust = KeyStore.getInstance("PKCS12").also { it.load(null, password) }
        Files.newOutputStream(empty).use { trust.store(it, password) }
        trust.setCertificateEntry("trusted-only", certificate)
        Files.newOutputStream(trustOnly).use { trust.store(it, password) }
        Files.writeString(database, "Nonempty sentinel: must never be opened as SQLite")
    }

    fun arguments(port: Int = 1, trust: Path = trustOnly) = Arguments("sync", "run", database,
        mapOf("keystore" to server.toString(), "truststore" to trust.toString(), "listen" to "127.0.0.1:$port"))

    fun snapshot(): Map<String, String> = Files.list(directory).use { paths ->
        paths.toList().associate { it.fileName.toString() to digest(Files.readAllBytes(it)) }
    }

    override fun close() {
        password.fill('\u0000')
        Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(directory)
    }

    companion object {
        private const val KEYTOOL_SECONDS = 30L
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
