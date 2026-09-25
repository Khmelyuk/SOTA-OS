package sotaos.test.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.SotaId
import sotaos.security.HashedBearerPeerCredentialAuthenticator

class PeerCredentialAuthenticatorTest : FunSpec({
    test("valid bearer credentials resolve to the configured peer") {
        val peer = SotaId("peer-a")
        val authenticator = HashedBearerPeerCredentialAuthenticator.fromBearerTokens(mapOf(peer to "secret-a"))
        authenticator.authenticate("Bearer secret-a") shouldBe peer
        authenticator.authenticate("Bearer wrong") shouldBe null
        authenticator.authenticate("Basic secret-a") shouldBe null
        authenticator.authenticate("Bearer secret a") shouldBe null
    }

    test("empty credential configuration and blank tokens are rejected") {
        val peer = SotaId("peer-a")
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            HashedBearerPeerCredentialAuthenticator.fromBearerTokens(emptyMap())
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            HashedBearerPeerCredentialAuthenticator.fromBearerTokens(mapOf(peer to ""))
        }
    }
})
