package sotaos.security

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

interface EventSigner {
    fun sign(contentHash: String): String
    fun verify(contentHash: String, signature: String): Boolean
}

class Ed25519EventSigner private constructor(
    private val privateKey: PrivateKey?,
    private val publicKey: PublicKey
) : EventSigner {
    constructor(keyPair: KeyPair) : this(keyPair.private, keyPair.public)
    constructor(publicKey: PublicKey) : this(null, publicKey)
    fun publicKey(): PublicKey = publicKey
    fun publicKeyEncoded(): String = Base64.getEncoder().encodeToString(publicKey.encoded)

    override fun sign(contentHash: String): String {
        val key = privateKey ?: error("This signer has no private key.")
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(key)
        signature.update(message(contentHash))
        return PREFIX + Base64.getEncoder().withoutPadding().encodeToString(signature.sign())
    }

    override fun verify(contentHash: String, signature: String): Boolean {
        if (!signature.startsWith(PREFIX)) return false
        return runCatching {
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message(contentHash))
            verifier.verify(Base64.getDecoder().decode(signature.removePrefix(PREFIX)))
        }.getOrDefault(false)
    }

    private fun message(contentHash: String): ByteArray {
        require(contentHash.matches(HEX_HASH)) { "Event content hash must be lowercase SHA-256 hex." }
        return contentHash.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val ALGORITHM = "Ed25519"
        private const val PREFIX = "ed25519:"
        private val HEX_HASH = Regex("[0-9a-f]{64}")
        fun generate(): Ed25519EventSigner = Ed25519EventSigner(
            KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        )

        fun fromPublicKeyEncoded(encoded: String): Ed25519EventSigner {
            val bytes = Base64.getDecoder().decode(encoded)
            val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
            return Ed25519EventSigner(key)
        }
    }
}
