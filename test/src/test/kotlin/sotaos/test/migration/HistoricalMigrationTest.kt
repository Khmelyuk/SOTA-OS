package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.test.acceptance.SignedActionFixture
import java.sql.DriverManager

class HistoricalMigrationTest : FunSpec({
    test("frozen Git schemas are healthy populated databases before current initialization") {
        for (schema in HistoricalSchema.entries) HistoricalFixture(schema).use { f ->
            f.sql { connection ->
                connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
                val columns = connection.query("PRAGMA table_info(decision)").map { it[1] }
                ("mission_snapshot_json" in columns) shouldBe false
                val credentialColumns = connection.query("PRAGMA table_info(local_credential)").map { it[1] }
                ("revision" in credentialColumns) shouldBe (schema == HistoricalSchema.KNOWLEDGE_VERSIONS)
                connection.query("SELECT COUNT(*) FROM knowledge") shouldBe listOf(listOf("2"))
                connection.query("SELECT position FROM sync_journal") shouldBe listOf(listOf("7"))
            }
        }
    }

    test("upgrades preserve every historical column and row without invented authority or signed evidence") {
        for (schema in HistoricalSchema.entries) HistoricalFixture(schema).use { f ->
            val before = f.sql { it.capture() }
            f.open()
            repeat(3) {
                f.reopen()
                f.assertOriginalRows(before)
                f.sql { connection ->
                    connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                    connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
                    connection.query("SELECT mission_snapshot_json FROM decision") shouldBe listOf(listOf(null))
                    connection.query("SELECT parent_authority_id FROM authority WHERE authority_id = 'root'") shouldBe
                        listOf(listOf(null))
                    for (table in listOf("verified_sync_record", "historical_record_approval", "trusted_peer")) {
                        connection.query("SELECT COUNT(*) FROM $table") shouldBe listOf(listOf("0"))
                    }
                    val operations = connection.query("SELECT operation FROM local_credential_audit ORDER BY audit_id")
                    operations shouldBe if (schema == HistoricalSchema.DURABLE_P09) listOf(listOf("LEGACY_BASELINE"))
                        else listOf(listOf("ENROLL"), listOf("ROTATE"), listOf("REVOKE"))
                }
            }
        }
    }

    test("upgraded table columns defaults primary keys and foreign keys match a fresh current store") {
        val expected = SignedActionFixture().use { fresh ->
            DriverManager.getConnection("jdbc:sqlite:${fresh.path}").use { it.schemaShape() }
        }
        for (schema in HistoricalSchema.entries) HistoricalFixture(schema).use { f ->
            f.open()
            f.sql { it.schemaShape() } shouldBe expected
        }
    }

    test("failed opening preserves historical rows and a later retry completes the additive upgrade once") {
        HistoricalFixture(HistoricalSchema.DURABLE_P09).use { f ->
            val before = f.sql { it.capture() }
            f.execute("""CREATE TABLE local_credential_audit (
                audit_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                unit_id TEXT NOT NULL, login_handle TEXT NOT NULL, revision INTEGER NOT NULL,
                operation TEXT NOT NULL, actor_person_id TEXT, occurred_at TEXT NOT NULL,
                FOREIGN KEY(unit_id, login_handle) REFERENCES local_credential(unit_id, login_handle))""")
            f.execute("""CREATE TRIGGER fixture_migration_failure BEFORE INSERT ON local_credential_audit
                BEGIN SELECT RAISE(ABORT, 'injected migration failure'); END""")
            val failure = shouldThrow<Exception> { f.open() }
            failure.message.orEmpty().contains("injected migration failure") shouldBe true
            f.assertOriginalRows(before)
            f.sql { it.query("SELECT COUNT(*) FROM local_credential_audit") } shouldBe listOf(listOf("0"))
            f.execute("DROP TRIGGER fixture_migration_failure")
            f.open()
            repeat(2) { f.reopen() }
            f.assertOriginalRows(before)
            f.sql { it.query("SELECT operation, actor_person_id FROM local_credential_audit") } shouldBe
                listOf(listOf("LEGACY_BASELINE", null))
            f.sql { it.query("PRAGMA foreign_key_check") } shouldBe emptyList()
        }
    }
})

private fun HistoricalFixture.assertOriginalRows(before: Map<String, HistoricalRows>) = sql { connection ->
    before.forEach { (table, image) -> connection.rows(table, image.columns) shouldBe image.rows }
}
