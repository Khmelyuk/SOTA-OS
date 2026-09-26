package sotaos.security

import sotaos.application.ports.PeerTrustRepository
import sotaos.domain.shared.SotaId
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Random 256-bit secrets are returned once; only SHA-256 verifiers are persisted. */
object PeerCredentials {
    private const val TOKEN_BYTES = 32
    fun generate(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class RegistryPeerAuthenticator(private val peers: PeerTrustRepository) : PeerCredentialAuthenticator {
    override fun authenticate(authorizationHeader: String): SotaId? {
        val token = authorizationHeader.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) } ?: return null
        val presented = PeerCredentials.hash(token).toByteArray(Charsets.US_ASCII)
        val matches = peers.all().filter { peer ->
            val matchesHash = MessageDigest.isEqual(presented, peer.credentialHash.toByteArray(Charsets.US_ASCII))
            peer.active && matchesHash
        }
        return matches.singleOrNull()?.policy?.peer
    }
}
