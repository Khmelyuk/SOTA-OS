package sotaos.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files

class NumberedMigrationTest : FunSpec({
    test("direct and intermediate upgrades agree and repeated opening does not repeat data migrations") {
        withProbeDatabase { direct ->
            withProbeDatabase { staged ->
                openProbe(direct)
                openProbe(staged, probePlan(2))
                sqlRows(staged, "PRAGMA user_version") shouldBe listOf(listOf("2"))
                openProbe(staged)
                repeat(3) {
                    openProbe(direct)
                    openProbe(staged)
                    assertCompleted(direct)
                    snapshot(staged) shouldBe snapshot(direct)
                }
            }
        }
    }
    for (boundary in MigrationBoundary.entries) {
        test("${boundary.name} rolls back every pending step and retry preserves data") {
            withProbeDatabase { path ->
                val before = snapshot(path)
                shouldThrow<MigrationInterrupted> {
                    openProbe(path) { InterruptMigrationDriver(it, boundary) }
                }
                snapshot(path) shouldBe before
                openProbe(path)
                assertCompleted(path)
            }
        }
    }
    test("failure from an intermediate version preserves that committed version") {
        withProbeDatabase { path ->
            openProbe(path, probePlan(2))
            val before = snapshot(path)
            shouldThrow<MigrationInterrupted> {
                openProbe(path) { InterruptMigrationDriver(it, MigrationBoundary.AFTER_MARKER) }
            }
            snapshot(path) shouldBe before
            openProbe(path)
            assertCompleted(path)
        }
    }
    test("legacy adoption completes one before numbered failure and retry") {
        withProbeDatabase { path ->
            executeSql(path, "PRAGMA user_version = 0")
            shouldThrow<MigrationInterrupted> {
                openProbe(path) { InterruptMigrationDriver(it, MigrationBoundary.DATA) }
            }
            sqlRows(path, "PRAGMA user_version") shouldBe listOf(listOf("1"))
            sqlRows(path, "SELECT * FROM person") shouldBe listOf(listOf("retained", "2026-11-27T00:00:00Z"))
            openProbe(path)
            assertCompleted(path)
        }
    }
    test("fresh creation skips backfills and rolls back schema guards and marker together") {
        withProbeDatabase { path ->
            Files.delete(path)
            val normal = probePlan()
            val fresh = StoreMigrationPlan(normal.schema, normal.afterStep.mapValues {
                { _: app.cash.sqldelight.db.SqlDriver -> error("Fresh creation must skip backfills") }
            }, normal.installCurrent)
            shouldThrow<MigrationInterrupted> {
                openProbe(path, fresh) { InterruptMigrationDriver(it, MigrationBoundary.AFTER_MARKER) }
            }
            sqlRows(path, "PRAGMA user_version") shouldBe listOf(listOf("0"))
            sqlRows(path, "SELECT name FROM sqlite_master") shouldBe emptyList()
            openProbe(path, fresh)
            sqlRows(path, "PRAGMA user_version") shouldBe listOf(listOf("3"))
            sqlRows(path, "SELECT * FROM migration_audit") shouldBe emptyList()
        }
    }
    test("unsupported versions fail without writes even with a registered upgrade plan") {
        withProbeDatabase { path ->
            for (version in listOf(-1, 4, Int.MAX_VALUE)) {
                executeSql(path, "PRAGMA user_version = $version")
                val before = Files.readAllBytes(path).toList()
                shouldThrow<UnsupportedSchemaVersionException> { openProbe(path) }.version shouldBe version.toLong()
                Files.readAllBytes(path).toList() shouldBe before
            }
        }
    }
    test("missing and extraneous runtime registrations fail before opening") {
        shouldThrow<IllegalArgumentException> { StoreMigrationPlan(schema = ProbeSchema()) }
        shouldThrow<IllegalArgumentException> {
            StoreMigrationPlan(schema = ProbeSchema(), afterStep = mapOf(1L to {}, 3L to {}))
        }
        shouldThrow<IllegalArgumentException> { StoreMigrationPlan(afterStep = mapOf(1L to {})) }
    }
    test("opening inside an outer transaction is rejected before schema writes") {
        withProbeDatabase { path ->
            val before = snapshot(path)
            JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                SotaOsDatabase(driver).transaction {
                    shouldThrow<IllegalStateException> { SqlDelightStore(driver, probePlan()) }
                }
            }
            snapshot(path) shouldBe before
        }
    }
    test("target validation failure rolls back and permits retry") {
        withProbeDatabase { path ->
            val before = snapshot(path)
            val normal = probePlan()
            val plan = StoreMigrationPlan(normal.schema, normal.afterStep, normal.installCurrent) {
                throw MigrationInterrupted()
            }
            shouldThrow<MigrationInterrupted> { openProbe(path, plan) }
            snapshot(path) shouldBe before
            openProbe(path)
            assertCompleted(path)
        }
    }
    test("a busy commit returns no store and closing the failed driver permits retry") {
        withProbeDatabase { path ->
            val before = snapshot(path)
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { reader ->
                reader.autoCommit = false
                reader.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM person").use { check(it.next()) }
                }
                var markerWritten = false
                shouldThrow<java.sql.SQLException> {
                    openProbe(path) { driver ->
                        InterruptMigrationDriver(driver, MigrationBoundary.AFTER_MARKER) { markerWritten = true }
                    }
                }
                markerWritten shouldBe true
                reader.rollback()
            }
            snapshot(path) shouldBe before
            openProbe(path)
            assertCompleted(path)
        }
    }
    test("foreign key validation failure rolls back deferred data writes") {
        withProbeDatabase { path ->
            val before = snapshot(path)
            val normal = probePlan()
            val plan = StoreMigrationPlan(normal.schema, normal.afterStep, normal.installCurrent) { driver ->
                driver.sql("PRAGMA defer_foreign_keys = ON")
                driver.sql("INSERT INTO migration_audit VALUES ('missing')")
            }
            shouldThrow<IllegalStateException> { openProbe(path, plan) }
            snapshot(path) shouldBe before
            openProbe(path)
            assertCompleted(path)
        }
    }
})

internal fun snapshot(path: java.nio.file.Path) = listOf(
    sqlRows(path, "PRAGMA user_version"),
    sqlRows(path, "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"),
    sqlRows(path, "SELECT * FROM person"),
    if (sqlRows(path, "SELECT name FROM sqlite_master WHERE name = 'migration_audit'").isEmpty()) emptyList()
    else sqlRows(path, "SELECT * FROM migration_audit")
)

internal fun assertCompleted(path: java.nio.file.Path) {
    sqlRows(path, "PRAGMA user_version") shouldBe listOf(listOf("3"))
    sqlRows(path, "SELECT * FROM person") shouldBe
        listOf(listOf("retained", "2026-11-27T00:00:00Z", "backfilled"))
    sqlRows(path, "SELECT * FROM migration_audit") shouldBe listOf(listOf("retained"))
    sqlRows(path, "PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
    sqlRows(path, "PRAGMA foreign_key_check") shouldBe emptyList()
    shouldThrow<java.sql.SQLException> { executeSql(path, "DELETE FROM person WHERE person_id = 'retained'") }
}
