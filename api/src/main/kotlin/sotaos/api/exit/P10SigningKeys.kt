package sotaos.api.exit

import sotaos.security.Ed25519EventSigner
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyStore
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey

/** Load local signing material only; trust comes from an independently provisioned actor key. */
object P10SigningKeys {
    fun load(path: Path, password: CharArray, alias: String, expectedPublicKey: String): Ed25519EventSigner {
        require(alias.isNotBlank()) { "A signing alias is required." }
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(path).use { store.load(it, password) }
        val privateKey = store.getKey(alias, password)
        val publicKey = store.getCertificate(alias)?.publicKey
        require(privateKey is EdECPrivateKey && privateKey.params.name == "Ed25519" &&
            publicKey is EdECPublicKey && publicKey.params.name == "Ed25519") {
            "The signing alias must contain an Ed25519 private key and certificate."
        }
        val signer = Ed25519EventSigner(KeyPair(publicKey, privateKey))
        val verifier = Ed25519EventSigner.fromPublicKeyEncoded(expectedPublicKey)
        require(verifier.verify(PROBE_HASH, signer.sign(PROBE_HASH))) {
            "The signing key does not match the current authenticated actor key."
        }
        return signer
    }

    private const val SHA256_HEX_LENGTH = 64
    private val PROBE_HASH = "0".repeat(SHA256_HEX_LENGTH)
}
