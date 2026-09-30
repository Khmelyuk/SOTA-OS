package sotaos.api.sync

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Explicit PKCS12 key/trust material; no trust-all manager or hostname-verification bypass. */
object P09Tls {
    fun server(path: Path, password: CharArray): SSLContext {
        val store = load(path, password)
        require(store.aliases().toList().any(store::isKeyEntry)) { "TLS server requires a private key entry." }
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
