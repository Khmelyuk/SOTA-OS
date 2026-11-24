package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*

class RuntimeSignedMigrationTest : FunSpec({
    test("pre-retirement receipt admits exact signed replay while changed and unknown old-key facts stay denied") {
        HistoricalFixture(HistoricalSchema.RUNTIME_SIGNED).use { f ->
            val store = f.open()
            val runtime = historyRuntime(store)
            val record = f.signedRecord("verified-before-retirement")
            val receipts = SqlDelightVerifiedRecordRepository(store.database)
            val receipt = requireNotNull(receipts.find(record.event.id))
            receipt.key.keyId shouldBe "remote-key-1"
            receipt.canonicalRecord shouldBe JsonSyncRecordCodec().encode(record)
            SqlDelightRepositories(store.database).actorSigningKeys.findByActor(historyActor) shouldBe null
            runtime.receive(record).acceptedThrough shouldBe 1
            val before = f.sql { it.capture() }
            val unknown = f.signedRecord("unknown-old-key-history")
            runtime.integrity.check(unknown)
            Ed25519EventSigner.fromPublicKeyEncoded(receipt.key.publicKeyBase64)
                .verify(unknown.event.contentHash, requireNotNull(unknown.event.signature)) shouldBe true
            val mutations = listOf(record.copy(event = record.event.copy(payload = mapOf("fact" to "changed"))),
                record.copy(event = record.event.copy(signature = null)),
                record.copy(parents = setOf(EventId("invented"))),
                unknown)
            mutations.forEach { shouldThrow<IllegalArgumentException> { runtime.receive(it, 1) } }
            f.assertPreserved(before)
            val reopened = f.reopen()
            SqlDelightVerifiedRecordRepository(reopened.database).find(record.event.id) shouldBe receipt
            historyRuntime(reopened).receive(record).acceptedThrough shouldBe 1
        }
    }

    test("historical approval survives migration and failed receipt insertion rolls back before retry") {
        HistoricalFixture(HistoricalSchema.RUNTIME_SIGNED).use { f ->
            val store = f.open()
            val runtime = historyRuntime(store)
            val approved = f.signedRecord("approved-offline-history")
            val approvals = SqlDelightHistoricalApprovalRepository(store.database)
            val original = requireNotNull(approvals.find(approved.event.id))
            val before = f.sql { it.capture() }
            f.execute("""CREATE TRIGGER fail_historical_receipt BEFORE INSERT ON verified_sync_record
                BEGIN SELECT RAISE(ABORT, 'injected historical receipt failure'); END""")
            val failure = shouldThrow<Exception> { runtime.receive(approved, 1) }
            failure.message.orEmpty().contains("injected historical receipt failure") shouldBe true
            f.assertPreserved(before)
            f.execute("DROP TRIGGER fail_historical_receipt")
            runtime.receive(approved, 1).acceptedThrough shouldBe 2
            val receipt = requireNotNull(SqlDelightVerifiedRecordRepository(store.database).find(approved.event.id))
            receipt.historicalApprovalTarget shouldBe original.audit.target
            receipt.verifiedAt shouldBe historyClock
            receipt.canonicalRecord shouldBe original.canonicalRecord
            val reopened = f.reopen()
            SqlDelightHistoricalApprovalRepository(reopened.database).find(approved.event.id) shouldBe original
            SqlDelightVerifiedRecordRepository(reopened.database).find(approved.event.id) shouldBe receipt
            historyRuntime(reopened).receive(approved, 1).acceptedThrough shouldBe 2
        }
    }

    test("revoked approval remains denied and migration never creates a receipt for unreceived history") {
        HistoricalFixture(HistoricalSchema.RUNTIME_SIGNED).use { f ->
            f.open()
            val store = f.reopen()
            val revoked = f.signedRecord("revoked-offline-history")
            val approvals = SqlDelightHistoricalApprovalRepository(store.database)
            val withdrawal = requireNotNull(approvals.revocation(revoked.event.id))
            withdrawal.operation shouldBe "history.revoke"
            val before = f.sql { it.capture() }
            shouldThrow<IllegalArgumentException> { historyRuntime(store).receive(revoked, 1) }
            SqlDelightVerifiedRecordRepository(store.database).find(revoked.event.id) shouldBe null
            SqlDelightRepositories(store.database).events.findById(revoked.event.id) shouldBe null
            f.assertPreserved(before)
        }
    }

    test("active key peer revisions and revoked peer denial survive upgrade and restart") {
        HistoricalFixture(HistoricalSchema.RUNTIME_SIGNED).use { f ->
            f.open()
            val store = f.reopen()
            val keys = SqlDelightRepositories(store.database).actorSigningKeys
            keys.findByActor(SubjectRef.Person(PersonId("local-author")))?.keyId shouldBe "local-active-key"
            keys.findByActor(historyActor) shouldBe null
            val peers = SqlDelightPeerTrustRepository(store.database)
            peers.find(historyPeer)?.revision shouldBe 2
            peers.find(historyPeer)?.active shouldBe true
            peers.find(SotaId("retired-peer"))?.revision shouldBe 3
            peers.find(SotaId("retired-peer"))?.active shouldBe false
            val auth = RegistryPeerAuthenticator(peers)
            auth.authenticate("Bearer public-historical-peer-token") shouldBe historyPeer
            auth.authenticate("Bearer public-retired-peer-token") shouldBe null
            val before = f.sql { it.capture() }
            val runtime = historyRuntime(store)
            shouldThrow<IllegalStateException> { runtime.exchange("Bearer public-retired-peer-token", "{}") }
            f.assertPreserved(before)
            f.execute("UPDATE trusted_peer SET active = 0 WHERE peer_id = 'remote'")
            shouldThrow<IllegalStateException> { runtime.receive(f.signedRecord("verified-before-retirement")) }
        }
    }
})
