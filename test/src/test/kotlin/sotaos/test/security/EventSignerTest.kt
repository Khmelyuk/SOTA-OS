package sotaos.test.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.security.Ed25519EventSigner

class EventSignerTest : FunSpec({
    val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    test("Ed25519 signatures verify only for the signed content hash") {
        val signer = Ed25519EventSigner.generate()
        val signature = signer.sign(hash)
        signature.startsWith("ed25519:") shouldBe true
        signer.verify(hash, signature) shouldBe true
        signer.verify(hash.replaceFirst('0', '1'), signature) shouldBe false
        signer.verify(hash, "unsupported:signature") shouldBe false
    }

    test("a public-key-only verifier can verify an event signature") {
        val signingKey = Ed25519EventSigner.generate()
        val verifier = Ed25519EventSigner(signingKey.publicKey())
        verifier.verify(hash, signingKey.sign(hash)) shouldBe true
    }

    test("a public key survives Base64 X.509 serialization") {
        val signingKey = Ed25519EventSigner.generate()
        val verifier = Ed25519EventSigner.fromPublicKeyEncoded(signingKey.publicKeyEncoded())
        verifier.verify(hash, signingKey.sign(hash)) shouldBe true
    }
})
