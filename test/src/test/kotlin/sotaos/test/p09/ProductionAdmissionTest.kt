package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.ActorSigningKeyRecord
import sotaos.domain.shared.*
import sotaos.domain.sync.*
import sotaos.security.Ed25519EventSigner

class ProductionAdmissionTest : FunSpec({
    test("real composition accepts signed exchange and preserves replay") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.exchange(token, record).acceptedThrough shouldBe 1
            f.exchange(token, record).batch.records.size shouldBe 1
            f.repositories.events.findById(record.event.id) shouldBe record.event
        }
    }
    test("payload hash signature and sync metadata tampering are rejected without writes") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            val mutations = listOf(
                record.copy(event = record.event.copy(payload = mapOf("fact" to "forged"))),
                record.copy(origin = localId),
                record.copy(parents = setOf(EventId("forged-parent"))),
                record.copy(assertion = StateAssertion(SyncEntity(SyncEntityKind.AUTHORITY, "injected"),
                    context, Validity(now, null), mapOf("scope" to "all"))),
                record.copy(event = record.event.copy(signature = null)),
                record.copy(event = record.event.copy(signature = "ed25519:invalid")),
                record.copy(event = record.event.copy(contentHash = "0".repeat(64)))
            )
            mutations.forEach { changed -> shouldThrow<IllegalArgumentException> { f.exchange(token, changed) } }
            f.repositories.events.findById(record.event.id) shouldBe null
            f.exchange(token, record).acceptedThrough shouldBe 1
        }
    }
    test("even valid signatures cannot widen provenance author context or origin policy") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            val changed = listOf(
                record.copy(event = record.event.copy(provenance = record.event.provenance.copy(author = localActor))),
                record.copy(event = record.event.copy(context = Context("private"))),
                record.copy(origin = SotaId("unknown")),
                record.copy(event = record.event.copy(actor = localActor)),
                record.copy(event = record.event.copy(provenance = record.event.provenance.copy(
                    sourceEventId = EventId("unbound-source"))))
            )
            changed.forEach {
                shouldThrow<IllegalArgumentException> { f.exchange(token, f.runtime.integrity.sign(it, f.signer)) }
            }
        }
    }
    test("live key rotation and revocation affect existing runtime immediately") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val oldRecord = f.record()
            val replacement = Ed25519EventSigner.generate()
            f.runtime.keyProvisioning.rotate(invocation, authorityId,
                ActorSigningKeyRecord(remoteActor, "remote-key-2", replacement.publicKeyEncoded()), "remote-key-1")
            shouldThrow<IllegalArgumentException> { f.exchange(token, oldRecord) }
            val valid = f.runtime.integrity.sign(oldRecord, replacement)
            f.exchange(token, valid).acceptedThrough shouldBe 1
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-2")
            shouldThrow<IllegalArgumentException> { f.exchange(token, valid) }
        }
    }
    test("bad credentials are rejected before JSON decoding") {
        ProductionFixture().use { f ->
            f.enroll()
            shouldThrow<IllegalStateException> { f.runtime.exchange("Bearer unknown", "not JSON") }
        }
    }
})
