package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.api.sync.P09Runtime
import java.util.UUID
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.security.*

class HistoricalApprovalTest : FunSpec({
    test("new node with only current key imports one approved old-key record after restart") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            val current = f.repositories.actorSigningKeys.findByActor(remoteActor)
            val approval = f.approveHistory(record)
            f.receipts().find(record.event.id) shouldBe null
            f.repositories.events.findById(record.event.id) shouldBe null
            f.repositories.actorSigningKeys.findByActor(remoteActor) shouldBe current
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                val receiver = runtime(reopened)
                f.exchange(token, record, receiver).acceptedThrough shouldBe 1
                f.exchange(token, record, receiver).batch.records.single() shouldBe record
                val receipts = SqlDelightVerifiedRecordRepository(reopened.database)
                val receipt = requireNotNull(receipts.find(record.event.id))
                receipt.key shouldBe approval.key
                receipt.historicalApprovalTarget shouldBe historicalRecordTarget(record)
                receipt.verifiedAt shouldBe now
                SqlDelightHistoricalApprovalRepository(reopened.database).find(record.event.id) shouldBe approval
                SqlDelightRepositories(reopened.database).actorSigningKeys.findByActor(remoteActor) shouldBe current
            }
        }
    }
    test("explicit approval can verify history without installing any active author key") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "current-key")
            f.approveHistory(record)
            f.exchange(token, record).acceptedThrough shouldBe 1
            f.repositories.actorSigningKeys.findByActor(remoteActor) shouldBe null
            f.receipts().find(record.event.id)?.key shouldBe f.oldKey()
        }
    }
    test("approval never permits another backdated record or a signed substitution of the same event") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            f.approveHistory(record)
            val earlier = now.minusSeconds(3600)
            val substitutions = listOf(
                record.copy(event = record.event.copy(payload = mapOf("fact" to "changed"))),
                record.copy(parents = setOf(EventId("invented"))),
                record.copy(origin = localId),
                record.copy(event = record.event.copy(id = EventId("backdated"), timestamp = earlier,
                    provenance = record.event.provenance.copy(recordedAt = earlier)))
            )
            substitutions.forEach { changed ->
                shouldThrow<IllegalArgumentException> {
                    f.exchange(token, f.runtime.integrity.sign(changed, f.signer))
                }
            }
            f.repositories.events.findById(record.event.id) shouldBe null
            f.exchange(token, record).acceptedThrough shouldBe 1
        }
    }
    test("revoked approval blocks first import but cannot be replaced") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            f.approveHistory(record)
            f.runtime.historicalApprovals.revoke(invocation, authorityId, record.event.id)
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            shouldThrow<IllegalArgumentException> { f.approveHistory(record) }
            f.receipts().find(record.event.id) shouldBe null
            f.approvals().revocation(record.event.id)?.actor shouldBe operator
        }
    }
    test("revocation after committed import retains local verification history but peer revocation still denies") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            f.approveHistory(record)
            f.exchange(token, record)
            f.runtime.historicalApprovals.revoke(invocation, authorityId, record.event.id)
            f.exchange(token, record).acceptedThrough shouldBe 1
            f.runtime.peerProvisioning.revoke(invocation, authorityId, peerId, 1)
            shouldThrow<IllegalStateException> { f.exchange(token, record) }
        }
    }
    test("approval cannot bypass current origin context or sharing policy") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            f.approveHistory(record)
            val peer = requireNotNull(f.peers.find(peerId))
            f.peers.save(peer.copy(policy = peer.policy.copy(contexts = setOf(Context("private")))))
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.receipts().find(record.event.id) shouldBe null
            f.peers.save(peer.copy(policy = peer.policy.copy(importOrigins = setOf(localId))))
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.receipts().find(record.event.id) shouldBe null
        }
    }
    test("approval requires exact delegated scope independent operator and governance context") {
        ProductionFixture().use { f ->
            val record = f.record()
            shouldThrow<IllegalArgumentException> {
                f.runtime.historicalApprovals.approve(invocation, authorityId, record, f.oldKey(), "review")
            }
            f.authorizeHistory(record)
            listOf(invocation.copy(actor = remoteActor), invocation.copy(actor = SubjectRef.Sota(peerId)),
                invocation.copy(context = Context("wrong"))).forEach { denied ->
                shouldThrow<IllegalArgumentException> {
                    f.runtime.historicalApprovals.approve(denied, authorityId, record, f.oldKey(), "review")
                }
            }
            val different = f.record("other-event")
            shouldThrow<IllegalArgumentException> {
                f.runtime.historicalApprovals.approve(invocation, authorityId, different, f.oldKey(), "review")
            }
            f.approvals().find(record.event.id) shouldBe null
        }
    }
    test("inactive authority and RightsConstraint denial cannot approve or revoke history") {
        ProductionFixture().use { f ->
            val record = f.record()
            f.authorizeHistory(record)
            val granted = requireNotNull(f.repositories.authorities.findById(authorityId))
            f.repositories.authorities.save(granted.copy(state = LifecycleState.REVOKED))
            shouldThrow<IllegalArgumentException> {
                f.runtime.historicalApprovals.approve(invocation, authorityId, record, f.oldKey(), "review")
            }
            f.repositories.authorities.save(granted)
            val denied = P09Runtime(f.store, LocalSyncIdentity(localId, setOf(localActor)), governance,
                Clock { now }, IdGenerator { UUID.randomUUID().toString() },
                RightsConstraint { throw RightsConstraintViolation("DENY", "test policy") })
            shouldThrow<RightsConstraintViolation> {
                denied.historicalApprovals.approve(invocation, authorityId, record, f.oldKey(), "review")
            }
            f.approvals().find(record.event.id) shouldBe null
            f.approveHistory(record)
            shouldThrow<RightsConstraintViolation> {
                denied.historicalApprovals.revoke(invocation, authorityId, record.event.id)
            }
            f.approvals().revocation(record.event.id) shouldBe null
        }
    }
    test("approval rejects invalid signatures missing evidence and local producer exceptions") {
        ProductionFixture().use { f ->
            val record = f.record()
            f.authorizeHistory(record)
            shouldThrow<IllegalArgumentException> {
                f.runtime.historicalApprovals.approve(invocation, authorityId, record, f.oldKey(), " ")
            }
            shouldThrow<IllegalArgumentException> {
                val wrong = f.oldKey().copy(publicKeyBase64 = Ed25519EventSigner.generate().publicKeyEncoded())
                f.runtime.historicalApprovals.approve(invocation, authorityId, record, wrong, "review")
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.historicalApprovals.approve(invocation, authorityId,
                    record.copy(event = record.event.copy(signature = null)), f.oldKey(), "review")
            }
            val local = f.runtime.integrity.sign(record.copy(origin = localId), f.signer)
            f.authorizeHistory(local)
            shouldThrow<IllegalArgumentException> {
                f.runtime.historicalApprovals.approve(invocation, authorityId, local, f.oldKey(), "review")
            }
            f.approvals().find(record.event.id) shouldBe null
        }
    }
})
