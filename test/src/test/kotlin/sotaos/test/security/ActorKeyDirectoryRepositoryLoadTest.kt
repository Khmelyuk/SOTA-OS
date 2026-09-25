package sotaos.test.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.ActorSigningKeyRecord
import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SubjectRef
import sotaos.security.ActorKeyDirectory
import sotaos.security.Ed25519EventSigner

class ActorKeyDirectoryRepositoryLoadTest : FunSpec({
    test("persisted actor key records reconstruct a verifying directory") {
        val actor = SubjectRef.Person(PersonId("person-reloaded"))
        val signer = Ed25519EventSigner.generate()
        val directory = ActorKeyDirectory.fromRecords(listOf(
            ActorSigningKeyRecord(actor, "key-1", signer.publicKeyEncoded())
        ))
        val restored = directory.signerFor(actor) ?: error("Actor key was not restored")
        restored.verify(TEST_HASH, signer.sign(TEST_HASH)) shouldBe true
    }
})

private const val TEST_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
