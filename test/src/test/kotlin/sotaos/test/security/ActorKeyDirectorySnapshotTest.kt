package sotaos.test.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SubjectRef
import sotaos.security.ActorKey
import sotaos.security.ActorKeyDirectory
import sotaos.security.Ed25519EventSigner

class ActorKeyDirectorySnapshotTest : FunSpec({
    test("active actor keys can be exported without private key material") {
        val actor = SubjectRef.Person(PersonId("person-a"))
        val signer = Ed25519EventSigner.generate()
        val directory = ActorKeyDirectory()
        directory.enroll(actor, ActorKey("key-1", signer.publicKey()))
        val exported = directory.activeKeys()[actor] ?: error("Missing actor key")
        exported.id shouldBe "key-1"
        exported.publicKey.encoded.contentEquals(signer.publicKey().encoded) shouldBe true
    }
})
