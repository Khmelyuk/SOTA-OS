package sotaos.test.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import sotaos.application.sync.SyncAdmission
import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.SotaId
import sotaos.security.Ed25519EventSigner
import sotaos.security.EventSignatureAdmission
import sotaos.test.sync.record

class EventSignatureAdmissionTest : FunSpec({
    test("configured actor signatures are verified during admission") {
        val unsigned = record("signed", SotaId("a")).copy(
            event = record("signed", SotaId("a")).event.copy(contentHash = TEST_HASH)
        )
        val signer = Ed25519EventSigner.generate()
        val signed = unsigned.copy(event = unsigned.event.copy(signature = signer.sign(unsigned.event.contentHash)))
        EventSignatureAdmission(allowAll, mapOf(unsigned.event.actor to signer))
            .check(SotaId("peer"), SyncDirection.IMPORT, listOf(signed))
    }

    test("configured actors cannot import unsigned or tampered events") {
        val unsigned = record("required", SotaId("a")).copy(
            event = record("required", SotaId("a")).event.copy(contentHash = TEST_HASH)
        )
        val signer = Ed25519EventSigner.generate()
        val policy = EventSignatureAdmission(allowAll, mapOf(unsigned.event.actor to signer))
        shouldThrow<IllegalArgumentException> {
            policy.check(SotaId("peer"), SyncDirection.IMPORT, listOf(unsigned))
        }
        val signed = unsigned.copy(event = unsigned.event.copy(signature = signer.sign(unsigned.event.contentHash)))
        val tampered = signed.copy(event = signed.event.copy(contentHash = "f".repeat(64)))
        shouldThrow<IllegalArgumentException> {
            policy.check(SotaId("peer"), SyncDirection.IMPORT, listOf(tampered))
        }
    }

    test("unconfigured actors retain legacy unsigned-event compatibility") {
        EventSignatureAdmission(allowAll, emptyMap()).check(
            SotaId("peer"), SyncDirection.IMPORT, listOf(record("legacy", SotaId("a")))
        )
    }
})

private val allowAll = SyncAdmission { _, _, _ -> }
private const val TEST_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
