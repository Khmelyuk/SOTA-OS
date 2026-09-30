package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.Clock
import sotaos.application.sync.PeerCheckpoint
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.VerifiedRecordSignatures

class HistoricalApprovalDurabilityTest : FunSpec({
    test("approval and revocation each roll back if provisioning audit fails") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            f.authorizeHistory(record)
            f.driver.execute(null, """CREATE TRIGGER fail_audit BEFORE INSERT ON provisioning_audit
BEGIN SELECT RAISE(ABORT, 'injected'); END""", 0)
            shouldThrow<Exception> { f.approveHistory(record) }
            f.approvals().find(record.event.id) shouldBe null
            f.driver.execute(null, "DROP TRIGGER fail_audit", 0)
            f.approveHistory(record)
            f.driver.execute(null, """CREATE TRIGGER fail_audit BEFORE INSERT ON provisioning_audit
BEGIN SELECT RAISE(ABORT, 'injected'); END""", 0)
            shouldThrow<Exception> { f.runtime.historicalApprovals.revoke(invocation, authorityId, record.event.id) }
            f.approvals().revocation(record.event.id) shouldBe null
            f.exchange(token, record).acceptedThrough shouldBe 1
        }
    }
    test("failed receipt insertion rolls back imported event journal and cursor without consuming approval") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            val approval = f.approveHistory(record)
            f.driver.execute(null, """CREATE TRIGGER fail_receipt BEFORE INSERT ON verified_sync_record
BEGIN SELECT RAISE(ABORT, 'injected'); END""", 0)
            shouldThrow<Exception> { f.exchange(token, record) }
            val journal = SqlDelightSyncRepository(f.store.database, JsonSyncRecordCodec())
            journal.records().size shouldBe 0
            journal.checkpoint(peerId) shouldBe PeerCheckpoint()
            f.repositories.events.findById(record.event.id) shouldBe null
            f.receipts().find(record.event.id) shouldBe null
            f.approvals().find(record.event.id) shouldBe approval
            f.driver.execute(null, "DROP TRIGGER fail_receipt", 0)
            f.exchange(token, record).acceptedThrough shouldBe 1
        }
    }
    test("denied export rolls back approved historical import and its verification receipt") {
        ProductionFixture().use { f ->
            val token = f.enrollCurrentKeyOnly()
            val record = f.record()
            val approval = f.approveHistory(record)
            val peer = requireNotNull(f.peers.find(peerId))
            f.peers.save(peer.copy(policy = peer.policy.copy(exportOrigins = setOf(localId))))
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.repositories.events.findById(record.event.id) shouldBe null
            f.receipts().find(record.event.id) shouldBe null
            f.approvals().find(record.event.id) shouldBe approval
        }
    }
    test("append rechecks approval after an earlier successful signature lookup") {
        ProductionFixture().use { f ->
            f.enrollCurrentKeyOnly()
            val record = f.record()
            f.approveHistory(record)
            val codec = JsonSyncRecordCodec()
            val verifier = VerifiedRecordSignatures(f.repositories.actorSigningKeys, f.receipts(), codec,
                f.runtime.integrity, Clock { now }, f.approvals())
            (verifier.historicalSigner(record) != null) shouldBe true
            f.runtime.historicalApprovals.revoke(invocation, authorityId, record.event.id)
            val journal = SqlDelightSyncRepository(f.store.database, codec, verifyAppended = verifier::remember)
            shouldThrow<IllegalArgumentException> { journal.transaction { journal.append(record) } }
            journal.records().size shouldBe 0
            f.repositories.events.findById(record.event.id) shouldBe null
        }
    }
    test("approvals and revocations retain immutable operator authority purpose and evidence") {
        ProductionFixture().use { f ->
            val record = f.record()
            val approval = f.approveHistory(record)
            approval.audit.actor shouldBe operator
            approval.audit.authority shouldBe authorityId
            approval.audit.purpose shouldBe invocation.purpose
            approval.evidenceReference shouldBe "review:offline-archive-1"
            f.runtime.historicalApprovals.revoke(invocation, authorityId, record.event.id)
            listOf("historical_record_approval", "historical_record_revocation").forEach { table ->
                shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM $table", 0) }
                shouldThrow<Exception> { f.driver.execute(null, "UPDATE $table SET purpose = 'changed'", 0) }
            }
            f.peers.audit().takeLast(2).map { it.operation } shouldBe listOf("history.approve", "history.revoke")
        }
    }
    test("additive migration keeps existing receipts without inventing historical approvals") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.exchange(token, record)
            val receipt = f.receipts().find(record.event.id)
            f.driver.execute(null, "DROP TABLE historical_record_revocation", 0)
            f.driver.execute(null, "DROP TABLE historical_record_approval", 0)
            f.driver.execute(null, "ALTER TABLE verified_sync_record DROP COLUMN historical_approval_target", 0)
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                SqlDelightVerifiedRecordRepository(reopened.database).find(record.event.id) shouldBe receipt
                SqlDelightHistoricalApprovalRepository(reopened.database).find(record.event.id) shouldBe null
                f.exchange(token, record, runtime(reopened)).acceptedThrough shouldBe 1
            }
        }
    }
})
