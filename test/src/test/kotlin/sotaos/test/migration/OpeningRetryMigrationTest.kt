package sotaos.test.migration

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.persistence.SqlDelightStore

class OpeningRetryMigrationTest : FunSpec({
    for (schema in HistoricalSchema.entries) {
        for (boundary in OpeningBoundary.entries) {
            test("${schema.directory} preserves history and retries after ${boundary.name}") {
                verifyOpeningRetry(schema, boundary)
            }
        }
    }
})

private enum class OpeningBoundary(val statement: String) {
    DECISION_COLUMN("ALTER TABLE decision ADD COLUMN mission_snapshot_json TEXT"),
    CREDENTIAL_BASELINE("INSERT INTO local_credential_audit")
}

private fun verifyOpeningRetry(schema: HistoricalSchema, boundary: OpeningBoundary) {
    HistoricalFixture(schema).use { reference ->
        reference.open()
        val expected = reference.sql { it.capture() }
        val shape = reference.sql { it.schemaShape() }
        val guards = reference.guards()
        HistoricalFixture(schema).use { interrupted ->
            val original = interrupted.sql { it.capture() }
            val originalGuards = interrupted.guards()
            interrupted.interruptAt(boundary)
            interrupted.assertOriginal(original, originalGuards)
            // The completed DDL remains committed even though store construction failed.
            interrupted.sql { it.query("PRAGMA table_info(decision)").any { row ->
                row[1] == "mission_snapshot_json"
            } } shouldBe true
            if (boundary == OpeningBoundary.CREDENTIAL_BASELINE) {
                interrupted.sql { it.capture().getValue("local_credential_audit") } shouldBe
                    expected.getValue("local_credential_audit")
            }
            interrupted.open()
            repeat(3) {
                interrupted.reopen()
                interrupted.assertOriginal(original, originalGuards)
                interrupted.sql { it.capture() } shouldBe expected
                interrupted.sql { it.schemaShape() } shouldBe shape
                interrupted.guards() shouldBe guards
            }
        }
    }
}

private fun HistoricalFixture.assertOriginal(
    original: Map<String, HistoricalRows>, originalGuards: List<List<String?>>
) = sql { connection ->
    original.forEach { (table, image) -> connection.rows(table, image.columns) shouldBe image.rows }
    guards().filter { row -> row.first() in originalGuards.map { it.first() } } shouldBe originalGuards
    connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
    connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
}

private fun HistoricalFixture.guards() = sql {
    it.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
}

private fun HistoricalFixture.interruptAt(boundary: OpeningBoundary) {
    JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        shouldThrow<OpeningInterrupted> {
            SqlDelightStore(InterruptedOpeningDriver(driver, boundary)).use { error("Expected opening failure") }
        }
    }
}

private class OpeningInterrupted : IllegalStateException("Injected failure after completed migration statement")

/** Throw only after SQLite completed the selected statement; do not simulate process death. */
private class InterruptedOpeningDriver(
    private val delegate: SqlDriver, private val boundary: OpeningBoundary
) : SqlDriver by delegate {
    override fun execute(identifier: Int?, sql: String, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        val result = delegate.execute(identifier, sql, parameters, binders)
        if (sql.trimStart().startsWith(boundary.statement)) throw OpeningInterrupted()
        return result
    }
}
