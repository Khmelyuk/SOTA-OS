package sotaos.api.sync

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Explicit PKCS12 key/trust material; no trust-all manager or hostname-verification bypass. */
object P09Tls {
    fun server(path: Path, password: CharArray): SSLContext = serverContext(path, password, Instant.now(), null)

    /** Reports all key-entry leaves from the same loaded store before strict date validation. */
    fun serverWithDiagnostics(path: Path, password: CharArray, at: Instant = Instant.now(),
        report: (List<P09CertificateInfo>) -> Unit): SSLContext = serverContext(path, password, at, report)

    private fun serverContext(path: Path, password: CharArray, at: Instant,
        report: ((List<P09CertificateInfo>) -> Unit)?): SSLContext {
        val store = load(path, password)
        val aliases = store.aliases().toList().filter(store::isKeyEntry).sorted()
        require(aliases.isNotEmpty()) { "TLS server requires a private key entry." }
        val certificates = aliases.map { alias ->
            val certificate = store.getCertificate(alias)
            require(certificate is X509Certificate) { "TLS server key requires an X.509 certificate." }
            alias to certificate
        }
        val observedAt = Date.from(at)
        report?.invoke(certificates.map { (alias, certificate) ->
            certificateInfo(alias, certificate, observedAt.toInstant())
        })
        certificates.forEach { (_, certificate) -> certificate.checkValidity(observedAt) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, password)
        return SSLContext.getInstance("TLS").also { it.init(keys.keyManagers, null, null) }
    }

    fun client(path: Path, password: CharArray): SSLContext {
        val store = load(path, password)
        require(store.size() > 0) { "An explicit TLS trust store is required." }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        return SSLContext.getInstance("TLS").also { it.init(null, trust.trustManagers, null) }
    }

    private fun load(path: Path, password: CharArray): KeyStore = KeyStore.getInstance("PKCS12").also { store ->
        Files.newInputStream(path).use { store.load(it, password) }
    }
}
