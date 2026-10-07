package sotaos.test.sync

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Ephemeral test key, trusted only by this test's client; hostname verification stays enabled. */
internal fun testTlsContext(): SSLContext {
    val password = "fixture-password".toCharArray()
    val keyStore = temporaryKeyStore(password)
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keys.init(keyStore, password)
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trust.init(keyStore)
    return SSLContext.getInstance("TLS").also { it.init(keys.keyManagers, trust.trustManagers, null) }
}

private fun temporaryKeyStore(password: CharArray): KeyStore {
    val directory = Files.createTempDirectory("sota-test-tls-")
    val path = directory.resolve("localhost.p12")
    try {
        generateTestKey(path, password)
        return KeyStore.getInstance("PKCS12").also { store ->
            Files.newInputStream(path).use { store.load(it, password) }
        }
    } finally {
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }
}

internal fun generateTestKey(path: Path, password: CharArray, startDate: String? = null) {
    val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
    val arguments = mutableListOf(
        keytool, "-genkeypair", "-alias", "sync-test", "-keyalg", "EC", "-groupname", "secp256r1",
        "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
        "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", String(password), "-noprompt"
    )
    startDate?.let { arguments.addAll(listOf("-startdate", it)) }
    val process = ProcessBuilder(arguments)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        check(process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Test key generation timed out." }
        check(process.exitValue() == 0) { "Test key generation failed." }
    } finally {
        if (process.isAlive) process.destroyForcibly().waitFor()
    }
}

private const val KEYTOOL_TIMEOUT_SECONDS = 30L
