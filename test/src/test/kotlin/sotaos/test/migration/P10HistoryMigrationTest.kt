package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*
import java.security.MessageDigest

class P10HistoryMigrationTest : FunSpec({
    test("populated historical P10 rows and guards survive upgrade and repeated reopening") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val before = f.sql { it.capture() }
            val guards = f.sql { it.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name") }
            guards.size shouldBe 50
            assertP10Guards(f)
            f.sql { connection ->
                connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
            }
            f.open()
            repeat(3) {
                f.reopen()
                f.assertPreserved(before)
                assertP10Guards(f)
                f.sql { connection ->
                    connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                    connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
                    val after = connection.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'")
                    after.filter { it.first() in guards.map { row -> row.first() } }
                        .sortedBy { it.first() } shouldBe guards
                }
            }
        }
    }

    test("completed exit retains exact archive approvals typed terms and responsibility without repeating writes") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val store = f.open()
            val exits = SqlDelightExitRepository(store.database)
            val document = requireNotNull(exits.export(f.completedId()))
            val digest = MessageDigest.getInstance("SHA-256").digest(document.json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            digest shouldBe document.sha256
            val archive = Json.parseToJsonElement(document.json).jsonObject
            for (kind in listOf("event", "knowledge", "evidence")) {
                archive.getValue(kind).jsonArray.size shouldBe 1
            }
            archive.getValue("knowledge").jsonArray.single().jsonObject.getValue("statement").jsonPrimitive.content
                .shouldBe("Approved historical statement")
            val relations = SqlDelightRelationInventory(store.database)
            val agreement = relations.agreements(completedTarget).single()
            agreement.state shouldBe ParticipationState.CLOSED
            agreement.terms.terms shouldBe listOf("Return equipment")
            agreement.obligations.single().state shouldBe ObligationState.RETAINED
            relations.trusts(completedTarget).single().state shouldBe ParticipationState.CLOSED
            val obligations = SqlDelightExitInventoryRepository(store.database).obligations(completedTarget)
            obligations.associate { it.id to it.state } shouldBe mapOf("equipment" to ObligationState.RETAINED,
                "obligation-chosen" to ObligationState.RETAINED, "already-fulfilled" to ObligationState.FULFILLED)
            val scope = SqlDelightExitScopeRepository(store.database)
            scope.hasMembership(completedTarget) shouldBe false
            scope.hasDelegations(completedTarget) shouldBe false
            scope.hasRelations(completedTarget) shouldBe false
            scope.hasMembership(pendingTarget) shouldBe true
            val before = f.sql { it.capture() }
            val service = exitRuntime(store).service
            repeat(2) {
                service.exportPortableData(exitInvocation, f.completedId()) shouldBe document
                service.terminateParticipation(exitInvocation, f.completedId(), document.sha256)
            }
            f.assertPreserved(before)
            assertUnaffected(store)
        }
    }

    test("six historical signed exit facts retain their receipts and unsigned pending exit cannot be upgraded") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val store = f.open()
            val codec = JsonSyncRecordCodec()
            val records = SqlDelightSyncRepository(store.database, codec).records()
            records.map(codec::encode) shouldBe f.exitVectors().getValue("records").jsonArray
                .map { it.jsonPrimitive.content }
            records.size shouldBe 6
            val receipts = SqlDelightVerifiedRecordRepository(store.database)
            records.forEachIndexed { index, record ->
                val receipt = requireNotNull(receipts.find(record.event.id))
                receipt.canonicalRecord shouldBe codec.encode(record)
                receipt.key.keyId shouldBe "historical-exit-key"
                SyncRecordIntegrity(codec).check(record)
                Ed25519EventSigner.fromPublicKeyEncoded(receipt.key.publicKeyBase64)
                    .verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
                record.parents shouldBe if (index == 0) emptySet() else setOf(records[index - 1].event.id)
            }
            val before = f.sql { it.capture() }
            val signed = SignedP10Runtime(store,
                LocalSyncIdentity(SotaId("historical-exit-node"), setOf(exitInvocation.actor)),
                Context("exit-governance"), Clock { exitClock }, IdGenerator { "unused" },
                { error("Historical unsigned exit must be rejected before signing") })
            shouldThrow<IllegalArgumentException> {
                signed.exit.service.revokeActiveDelegations(exitInvocation, f.pendingId())
            }
            f.assertPreserved(before)
            f.sql { it.query("SELECT COUNT(*) FROM verified_sync_record") } shouldBe listOf(listOf("6"))
        }
    }
})

private fun assertP10Guards(f: HistoricalFixture) {
    val columns = mapOf("exit_archive" to "document_json", "exit_transition" to "stage",
        "exit_governance_audit" to "evidence", "owned_exit_evidence" to "description",
        "relation_terms" to "terms_json", "relation_obligation" to "obligation_id", "exit_obligation" to "description")
    for ((table, column) in columns) {
        f.sql { it.query("SELECT COUNT(*) FROM $table").single().single()?.toInt()?.let { it > 0 } } shouldBe true
        shouldThrow<Exception> { f.execute("UPDATE $table SET $column = 'rewritten'") }
        shouldThrow<Exception> { f.execute("DELETE FROM $table") }
    }
    val failure = shouldThrow<Exception> {
        f.execute("UPDATE membership SET state = 'ACTIVE' WHERE collective_id = 'other'")
    }
    failure.message.orEmpty().contains("cannot add live commitments during exit") shouldBe true
}
