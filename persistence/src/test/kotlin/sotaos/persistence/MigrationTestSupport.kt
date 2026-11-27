package sotaos.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Test-only versions: production declarations and the immutable snapshot stay at one. */
internal class ProbeSchema(override val version: Long = 3) : SqlSchema<QueryResult.Value<Unit>> {
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SotaOsDatabase.Schema.create(driver)
        for (source in 1 until version) migrate(driver, source, source + 1)
        return QueryResult.Unit
    }

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
        vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        check(newVersion == oldVersion + 1)
        when (oldVersion) {
            1L -> driver.sql("ALTER TABLE person ADD COLUMN migration_probe TEXT")
            2L -> driver.sql("CREATE TABLE migration_audit (person_id TEXT NOT NULL REFERENCES person(person_id))")
            else -> error("Unexpected migration source $oldVersion")
        }
        return QueryResult.Unit
    }
}

internal fun probePlan(target: Long = 3): StoreMigrationPlan = StoreMigrationPlan(
    schema = ProbeSchema(target),
    afterStep = (1 until target).associateWith { source ->
        { driver: SqlDriver ->
            when (source) {
                1L -> driver.sql("UPDATE person SET migration_probe = 'backfilled'")
                2L -> driver.sql("INSERT INTO migration_audit SELECT person_id FROM person")
            }
        }
    },
    installCurrent = { driver ->
        installBaselineRuntimeSchema(driver)
        // A changed guard must be replaced, not silently kept by IF NOT EXISTS.
        driver.sql("DROP TRIGGER IF EXISTS migration_guard")
        driver.sql("""CREATE TRIGGER migration_guard BEFORE DELETE ON person
            BEGIN SELECT RAISE(ABORT, 'migration guard'); END""")
    },
    validateCurrent = { driver ->
        check(driver.queryRows("SELECT name FROM sqlite_master WHERE name = 'migration_guard'").size == 1)
    }
)

internal fun SqlDriver.sql(statement: String) = execute(null, statement, 0)

internal fun SqlDriver.queryRows(statement: String): List<List<String?>> = executeQuery(null, statement, { cursor ->
    val rows = mutableListOf<List<String?>>()
    while (cursor.next().value) rows += listOf(cursor.getString(0))
    QueryResult.Value(rows)
}, 0).value

internal fun openProbe(path: Path, plan: StoreMigrationPlan = probePlan(),
    wrap: (SqlDriver) -> SqlDriver = { it }) {
    JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
        driver.sql("PRAGMA foreign_keys = ON")
        SqlDelightStore(wrap(driver), plan)
    }
}

internal fun sqlRows(path: Path, query: String): List<List<String?>> =
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { result ->
                buildList { while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) }) }
            }
        }
    }

internal fun executeSql(path: Path, sql: String) = DriverManager.getConnection("jdbc:sqlite:$path").use {
    it.createStatement().use { statement -> statement.execute(sql) }
}

internal fun withProbeDatabase(journal: String = "DELETE", block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("sota-numbered-")
    val path = directory.resolve("store.db")
    try {
        executeSql(path, "PRAGMA journal_mode = $journal")
        openProbe(path, StoreMigrationPlan())
        executeSql(path, "INSERT INTO person VALUES ('retained', '2026-11-27T00:00:00Z')")
        executeSql(path, "CREATE TRIGGER migration_guard BEFORE DELETE ON person BEGIN SELECT 1; END")
        block(path)
    } finally {
        Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(directory)
    }
}

internal enum class MigrationBoundary(val statement: String, val before: Boolean = false) {
    SCHEMA("ALTER TABLE person"),
    DATA("UPDATE person SET migration_probe"),
    SECOND_STEP("INSERT INTO migration_audit"),
    GUARD_REMOVED("DROP TRIGGER IF EXISTS migration_guard"),
    GUARD_INSTALLED("CREATE TRIGGER migration_guard"),
    BEFORE_MARKER("PRAGMA user_version = 3", true),
    AFTER_MARKER("PRAGMA user_version = 3")
}

internal class MigrationInterrupted : IllegalStateException("Injected migration interruption")

internal class InterruptMigrationDriver(
    private val delegate: SqlDriver, private val boundary: MigrationBoundary,
    private val interrupt: () -> Unit = { throw MigrationInterrupted() }
) : SqlDriver by delegate {
    override fun execute(identifier: Int?, sql: String, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        val selected = sql.trimStart().startsWith(boundary.statement)
        if (selected && boundary.before) interrupt()
        val result = delegate.execute(identifier, sql, parameters, binders)
        if (selected && !boundary.before) interrupt()
        return result
    }
}
