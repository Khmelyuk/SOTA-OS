package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.Ed25519EventSigner

class HistoricalSignatureTest : FunSpec({
    test("exact verified history survives rotation revocation and SQLite restart") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.exchange(token, record)
            val proof = requireNotNull(f.history().find(record.event.id))
            proof.key.keyId shouldBe "remote-key-1"
            proof.canonicalRecord shouldBe JsonSyncRecordCodec().encode(record)
            val replacement = Ed25519EventSigner.generate()
            f.runtime.keyProvisioning.rotate(invocation, authorityId,
                ActorSigningKeyRecord(remoteActor, "remote-key-2", replacement.publicKeyEncoded()), "remote-key-1")
            f.exchange(token, record).acceptedThrough shouldBe 1
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-2")
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                val receiver = runtime(reopened)
                f.exchange(token, record, receiver).batch.records.single() shouldBe record
                SqlDelightVerifiedRecordRepository(reopened.database).find(record.event.id) shouldBe proof
                SqlDelightRepositories(reopened.database).actorSigningKeys.findByActor(remoteActor) shouldBe null
            }
        }
    }
    test("backdating an unknown old-key record never establishes historical trust") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            f.exchange(token, f.record())
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-1")
            val template = f.record("forged-old")
            val before = now.minusSeconds(3600)
            val backdated = f.runtime.integrity.sign(template.copy(event = template.event.copy(timestamp = before,
                provenance = template.event.provenance.copy(recordedAt = before))), f.signer)
            shouldThrow<IllegalArgumentException> { f.exchange(token, backdated) }
            f.history().find(backdated.event.id) shouldBe null
            f.repositories.events.findById(backdated.event.id) shouldBe null
        }
    }
    test("historical proof binds full metadata and signature and cannot bypass live peer trust") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.exchange(token, record)
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-1")
            val changed = listOf(
                record.copy(event = record.event.copy(payload = mapOf("fact" to "changed"))),
                record.copy(parents = setOf(EventId("invented"))),
                record.copy(event = record.event.copy(signature = null))
            )
            changed.forEach { mutation ->
                shouldThrow<IllegalArgumentException> { f.exchange(token, mutation) }
                if (mutation.event.signature != null) {
                    shouldThrow<IllegalArgumentException> {
                        f.exchange(token, f.runtime.integrity.sign(mutation, f.signer))
                    }
                }
            }
            f.runtime.peerProvisioning.revoke(invocation, authorityId, peerId, 1)
            shouldThrow<IllegalStateException> { f.exchange(token, record) }
        }
    }
    test("failed history insertion rolls back journal event and acknowledgement") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.driver.execute(null, """CREATE TRIGGER fail_history BEFORE INSERT ON verified_sync_record
                BEGIN SELECT RAISE(ABORT, 'injected'); END""", 0)
            shouldThrow<Exception> { f.exchange(token, record) }
            f.history().find(record.event.id) shouldBe null
            f.repositories.events.findById(record.event.id) shouldBe null
            val journal = SqlDelightSyncRepository(f.store.database, JsonSyncRecordCodec())
            journal.records().size shouldBe 0
            journal.checkpoint(peerId) shouldBe PeerCheckpoint()
            f.driver.execute(null, "DROP TRIGGER fail_history", 0)
            f.exchange(token, record).acceptedThrough shouldBe 1
        }
    }
    test("failed export policy leaves no historical admission proof") {
        ProductionFixture().use { f ->
            val token = f.enroll(f.policy.copy(exportOrigins = setOf(localId)))
            val record = f.record()
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
            f.history().find(record.event.id) shouldBe null
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-1")
            shouldThrow<IllegalArgumentException> { f.exchange(token, record) }
        }
    }
    test("verification evidence is append only") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.exchange(token, record)
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM verified_sync_record", 0) }
            shouldThrow<Exception> {
                f.driver.execute(null, "UPDATE verified_sync_record SET key_id = 'substituted'", 0)
            }
            f.history().find(record.event.id)?.key?.keyId shouldBe "remote-key-1"
        }
    }
    test("migration preserves old journal without inventing pre-retirement verification") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.exchange(token, record)
            f.driver.execute(null, "DROP TABLE verified_sync_record", 0)
            f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-1")
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                SqlDelightVerifiedRecordRepository(reopened.database).find(record.event.id) shouldBe null
                SqlDelightRepositories(reopened.database).events.findById(record.event.id) shouldBe record.event
                shouldThrow<IllegalArgumentException> { f.exchange(token, record, runtime(reopened)) }
            }
        }
    }
    test("local producer proof is committed before rotation and unknown stale local signatures are denied") {
        ProductionFixture().use { f ->
            f.enroll()
            val signer = Ed25519EventSigner.generate()
            f.runtime.keyProvisioning.enroll(invocation, authorityId,
                ActorSigningKeyRecord(localActor, "local-key", signer.publicKeyEncoded()))
            val template = f.record()
            val local = f.runtime.integrity.sign(template.copy(origin = localId,
                event = template.event.copy(actor = localActor,
                    provenance = template.event.provenance.copy(author = localActor))), signer)
            f.runtime.recordLocal(local)
            f.history().find(local.event.id)?.key?.keyId shouldBe "local-key"
            f.runtime.keyProvisioning.revoke(invocation, authorityId, localActor, "local-key")
            var exported = false
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    request.batch.records.single() shouldBe local
                    exported = true
                    return SyncResponse(request.id, peerId, request.batch.through, SyncBatch(0, 0, emptyList()))
                }
            }
            f.runtime.synchronize(peerId, transport).sent shouldBe 1
            exported shouldBe true
            val unknown = f.runtime.integrity.sign(local.copy(event = local.event.copy(id = EventId("new"))), signer)
            shouldThrow<IllegalArgumentException> { f.runtime.recordLocal(unknown) }
            f.repositories.events.findById(unknown.event.id) shouldBe null
        }
    }
})

private fun ProductionFixture.history() = SqlDelightVerifiedRecordRepository(store.database)
