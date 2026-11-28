package sotaos.test.migration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.Ed25519EventSigner
import sotaos.security.SyncRecordIntegrity
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** Disposable synthetic nodes only. No host configuration or private keys enter the backup. */
class StoppedNodeRecoveryTest : FunSpec({
    for (schema in listOf(HistoricalSchema.RUNTIME_SIGNED, HistoricalSchema.RUNTIME_P10)) {
        for (journal in listOf("DELETE", "WAL")) {
            test("stopped $journal ${schema.directory} backup restores exact history and current services") {
                HistoricalFixture(schema).use { fixture ->
                    fixture.open().close()
                    fixture.execute("PRAGMA journal_mode = $journal")
                    val before = fixture.sql { it.capture() }
                    val catalog = fixture.sql { it.recoveryCatalog() }
                    val backup = Files.createTempFile("sota-stopped-backup-", ".db")
                    val restored = Files.createTempFile("sota-restored-node-", ".db")
                    try {
                        Files.delete(backup) // VACUUM INTO requires an absent or empty destination.
                        fixture.sql { connection ->
                            connection.query("PRAGMA journal_mode") shouldBe listOf(listOf(journal.lowercase()))
                            connection.prepareStatement("VACUUM INTO ?").use {
                                it.setString(1, backup.toString())
                                it.execute()
                            }
                        }
                        val digest = backup.recoveryDigest()
                        Files.copy(backup, restored, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        restored.recoveryDigest() shouldBe digest
                        restored.recoverySql { connection ->
                            connection.capture() shouldBe before
                            connection.recoveryCatalog() shouldBe catalog
                            connection.assertRecoveryIntegrity()
                        }
                        repeat(2) {
                            restored.withRecoveryStore { store -> verifyRecoveredSignatures(store) }
                            restored.recoverySql { it.capture() shouldBe before }
                        }
                        restored.withRecoveryStore { store ->
                            if (schema == HistoricalSchema.RUNTIME_P10) resumeRecoveredExit(fixture, store)
                            else verifyRecoveredAdmission(fixture, store)
                        }
                        val resumed = restored.recoverySql { it.capture() }
                        restored.withRecoveryStore { verifyRecoveredSignatures(it) }
                        restored.recoverySql { it.capture() shouldBe resumed }
                        restored.recoverySql { it.assertRecoveryIntegrity() }
                        // Work on the replacement must not mutate the recovery point or original node.
                        backup.recoveryDigest() shouldBe digest
                        fixture.assertPreserved(before)
                    } finally {
                        listOf(backup, restored).forEach { path ->
                            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                                Files.deleteIfExists(Path.of("$path$suffix"))
                            }
                        }
                    }
                }
            }
        }
    }
})

private fun Connection.recoveryCatalog() = query(
    "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name"
)

private fun Connection.assertRecoveryIntegrity() {
    query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
    query("PRAGMA foreign_key_check") shouldBe emptyList()
    query("PRAGMA user_version") shouldBe listOf(listOf("1"))
}

private fun Path.recoveryDigest() = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(this))
    .joinToString("") { "%02x".format(it) }

private fun <T> Path.recoverySql(block: (Connection) -> T): T =
    DriverManager.getConnection("jdbc:sqlite:$this").use { connection ->
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        block(connection)
    }

private fun Path.withRecoveryStore(block: (SqlDelightStore) -> Unit) {
    JdbcSqliteDriver("jdbc:sqlite:$this").use { driver ->
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        SqlDelightStore(driver).use(block)
    }
}

private fun verifyRecoveredSignatures(store: SqlDelightStore) {
    val codec = JsonSyncRecordCodec()
    val records = SqlDelightSyncRepository(store.database, codec).records()
    records.isNotEmpty() shouldBe true
    val receipts = SqlDelightVerifiedRecordRepository(store.database)
    records.forEach { record ->
        val receipt = requireNotNull(receipts.find(record.event.id))
        receipt.canonicalRecord shouldBe codec.encode(record)
        SyncRecordIntegrity(codec).check(record)
        Ed25519EventSigner.fromPublicKeyEncoded(receipt.key.publicKeyBase64)
            .verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
    }
}

private fun resumeRecoveredExit(fixture: HistoricalFixture, store: SqlDelightStore) {
    val exits = SqlDelightExitRepository(store.database)
    val completed = requireNotNull(exits.export(fixture.completedId()))
    val runtime = exitRuntime(store)
    repeat(2) { runtime.preparePending(fixture.pendingId()) }
    val document = runtime.service.exportPortableData(exitInvocation, fixture.pendingId())
    repeat(2) {
        runtime.service.terminateParticipation(exitInvocation, fixture.pendingId(), document.sha256)
        runtime.service.exportPortableData(exitInvocation, fixture.completedId()) shouldBe completed
    }
    exits.transitions(fixture.pendingId()).map { it.stage } shouldBe ExitStage.entries
    SqlDelightExitScopeRepository(store.database).hasMembership(pendingTarget) shouldBe false
    SqlDelightExitInventoryRepository(store.database).obligations(pendingTarget).single().state shouldBe
        ObligationState.RETAINED
    assertUnaffected(store)
    verifyRecoveredSignatures(store)
}

private fun verifyRecoveredAdmission(fixture: HistoricalFixture, store: SqlDelightStore) {
    // Exact old-key receipts stay useful after restore; fresh old-key facts must still be denied.
    val runtime = historyRuntime(store)
    val known = fixture.signedRecord("verified-before-retirement")
    runtime.receive(known).acceptedThrough shouldBe 1
    shouldThrow<IllegalArgumentException> {
        runtime.receive(fixture.signedRecord("unknown-old-key-history"), 1)
    }
    shouldThrow<IllegalArgumentException> {
        runtime.receive(fixture.signedRecord("revoked-offline-history"), 1)
    }
    verifyRecoveredSignatures(store)
}
