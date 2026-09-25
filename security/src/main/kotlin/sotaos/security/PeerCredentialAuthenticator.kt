package sotaos.security

import sotaos.domain.shared.SotaId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface PeerCredentialAuthenticator {
    fun authenticate(authorizationHeader: String): SotaId?
}

class HashedBearerPeerCredentialAuthenticator private constructor(
    private val credentialHashes: Map<SotaId, ByteArray>
) : PeerCredentialAuthenticator {
    override fun authenticate(authorizationHeader: String): SotaId? {
        val token = authorizationHeader.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
        return token?.let { value ->
            val presented = digest(value)
            credentialHashes.entries.firstOrNull { (_, expected) -> MessageDigest.isEqual(presented, expected) }?.key
        }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        fun fromBearerTokens(tokensByPeer: Map<SotaId, String>): HashedBearerPeerCredentialAuthenticator {
            require(tokensByPeer.isNotEmpty()) { "At least one peer credential is required." }
            require(tokensByPeer.values.all(String::isNotBlank)) { "Peer credentials must not be blank." }
            return HashedBearerPeerCredentialAuthenticator(tokensByPeer.mapValues { (_, token) -> digest(token) })
        }

        private fun digest(token: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
    }
}
