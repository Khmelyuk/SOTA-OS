package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.test.acceptance.SignedActionFixture
import java.sql.DriverManager

class RuntimeSchemaMigrationTest : FunSpec({
    test("captured runtime guards exist before upgrade and every original row survives three reopens") {
        HistoricalFixture(HistoricalSchema.RUNTIME_SIGNED).use { f ->
            val before = f.sql { it.capture() }
            val guards = f.sql { it.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name") }
            guards.size shouldBe 50
            guards.any { it.first() == "verified_record_no_update" } shouldBe true
            guards.any { it.first() == "historical_record_approval_no_delete" } shouldBe true
            f.sql { connection ->
                connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
                connection.query("PRAGMA table_info(decision)").any { it[1] == "mission_snapshot_json" } shouldBe false
            }
            assertHistoryGuards(f)
            f.open()
            repeat(3) {
                f.reopen()
                f.assertPreserved(before)
                assertHistoryGuards(f)
                f.sql { connection ->
                    val after = connection.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'")
                    val retained = after.filter { row -> row.first() in guards.map { it.first() } }
                    retained.sortedBy { it.first() } shouldBe guards
                    connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                    connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
                }
            }
        }
    }

    test("captured runtime upgrades to the current table and foreign key contract") {
        val expected = SignedActionFixture().use { fresh ->
            DriverManager.getConnection("jdbc:sqlite:${fresh.path}").use { it.schemaShape() }
        }
        HistoricalFixture(HistoricalSchema.RUNTIME_SIGNED).use { f ->
            f.open()
            f.sql { it.schemaShape() } shouldBe expected
        }
    }
})

private fun assertHistoryGuards(fixture: HistoricalFixture) {
    val columns = mapOf("actor_signing_key_audit" to "key_id", "provisioning_audit" to "purpose",
        "verified_sync_record" to "key_id", "historical_record_approval" to "evidence_reference",
        "historical_record_revocation" to "purpose")
    for ((table, column) in columns) {
        fixture.sql { it.query("SELECT COUNT(*) FROM $table").single().single()?.toInt()?.let { it > 0 } } shouldBe true
        shouldThrow<Exception> { fixture.execute("UPDATE $table SET $column = 'rewritten'") }
        shouldThrow<Exception> { fixture.execute("DELETE FROM $table") }
    }
}
