package sotaos.test.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import sotaos.application.sync.SyncAdmission
import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.SotaId
import sotaos.security.ActorKey
import sotaos.security.ActorKeyDirectory
import sotaos.security.Ed25519EventSigner
import sotaos.security.EventSignatureAdmission
import sotaos.test.sync.record

class ActorKeyDirectoryTest : FunSpec({
    test("rotation accepts the new key and rejects the old key") {
        val actorEvent = record("rotation", SotaId("a")).copy(
            event = record("rotation", SotaId("a")).event.copy(contentHash = TEST_HASH)
        )
        val first = Ed25519EventSigner.generate()
        val second = Ed25519EventSigner.generate()
        val directory = ActorKeyDirectory()
        directory.enroll(actorEvent.event.actor, ActorKey("key-1", first.publicKey()))
        val policy = EventSignatureAdmission(allowAll, directory)
        val firstEvent = actorEvent.copy(event = actorEvent.event.copy(signature = first.sign(TEST_HASH)))
        policy.check(SotaId("peer"), SyncDirection.IMPORT, listOf(firstEvent))
        directory.rotate(actorEvent.event.actor, ActorKey("key-2", second.publicKey()))
        shouldThrow<IllegalArgumentException> { policy.check(SotaId("peer"), SyncDirection.IMPORT, listOf(firstEvent)) }
        val secondEvent = actorEvent.copy(event = actorEvent.event.copy(signature = second.sign(TEST_HASH)))
        policy.check(SotaId("peer"), SyncDirection.IMPORT, listOf(secondEvent))
    }

    test("revoked actors no longer pass signed-event admission") {
        val actorEvent = record("revoke", SotaId("a")).copy(
            event = record("revoke", SotaId("a")).event.copy(contentHash = TEST_HASH)
        )
        val signer = Ed25519EventSigner.generate()
        val directory = ActorKeyDirectory()
        directory.enroll(actorEvent.event.actor, ActorKey("key-1", signer.publicKey()))
        val signed = actorEvent.copy(event = actorEvent.event.copy(signature = signer.sign(TEST_HASH)))
        directory.revoke(actorEvent.event.actor, "key-1")
        shouldThrow<IllegalArgumentException> {
            EventSignatureAdmission(allowAll, directory).check(SotaId("peer"), SyncDirection.IMPORT, listOf(signed))
        }
    }
})

private val allowAll = SyncAdmission { _, _, _ -> }
private const val TEST_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
