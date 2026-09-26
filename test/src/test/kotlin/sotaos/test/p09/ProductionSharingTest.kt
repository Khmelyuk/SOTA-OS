package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.*
import sotaos.domain.sync.*
import sotaos.application.sync.*
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.protocol.JsonSyncRecordCodec

class ProductionSharingTest : FunSpec({
    test("allowed relay preserves origin and rejects a subsequently revoked origin") {
        ProductionFixture().use { f ->
            val origin = SotaId("origin")
            val token = f.enroll(f.policy.copy(importOrigins = setOf(origin), exportOrigins = setOf(origin)))
            f.repositories.authorities.save(f.authority.copy(scope = f.authority.scope.copy(
                resources = f.authority.scope.resources + "peer:origin")))
            f.runtime.peerProvisioning.enroll(invocation, authorityId, f.policy.copy(peer = origin))
            val record = f.runtime.integrity.sign(f.record().copy(origin = origin), f.signer)
            f.exchange(token, record).batch.records.single().origin shouldBe origin
            f.runtime.peerProvisioning.revoke(invocation, authorityId, origin, 1)
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
        }
    }
    test("trusted origin cannot be relayed without explicit sender permission") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val origin = SotaId("origin")
            f.repositories.authorities.save(f.authority.copy(scope = f.authority.scope.copy(
                resources = f.authority.scope.resources + "peer:origin")))
            f.runtime.peerProvisioning.enroll(invocation, authorityId, f.policy.copy(peer = origin))
            val record = f.runtime.integrity.sign(f.record().copy(origin = origin), f.signer)
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.repositories.events.findById(record.event.id) shouldBe null
        }
    }
    test("export denial rolls back received events and checkpoint") {
        ProductionFixture().use { f ->
            val token = f.enroll(f.policy.copy(exportOrigins = setOf(localId)))
            val record = f.record()
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.repositories.events.findById(record.event.id) shouldBe null
            val journal = SqlDelightSyncRepository(f.store.database, JsonSyncRecordCodec())
            journal.checkpoint(peerId) shouldBe PeerCheckpoint()
        }
    }
    test("production policy denies unsigned events even after their actor key is removed") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-1")
            shouldThrow<IllegalArgumentException> {
                f.exchange(token, record.copy(event = record.event.copy(signature = null)))
            }
        }
    }
    test("only explicitly permitted entities may carry signed assertions") {
        ProductionFixture().use { f ->
            val entity = SyncEntity(SyncEntityKind.AUTHORITY, "governed-authority")
            val token = f.enroll(f.policy.copy(assertionEntities = setOf(entity)))
            val assertion = StateAssertion(entity, context, Validity(now, null), mapOf("terms" to "scoped"))
            val allowed = f.runtime.integrity.sign(f.record().copy(assertion = assertion), f.signer)
            val denied = f.runtime.integrity.sign(allowed.copy(assertion = assertion.copy(
                entity = SyncEntity(SyncEntityKind.AUTHORITY, "unrelated-authority"))), f.signer)
            shouldThrow<IllegalArgumentException> { f.exchange(token, denied) }
            f.exchange(token, allowed).acceptedThrough shouldBe 1
        }
    }
    test("import direction is enforced before persistence even for signed records") {
        ProductionFixture().use { f ->
            val token = f.enroll(f.policy.copy(directions = setOf(SyncDirection.EXPORT)))
            val record = f.record()
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.repositories.events.findById(record.event.id) shouldBe null
        }
    }
})
