package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.persistence.UnsupportedSchemaVersionException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class StoreOpeningTest : FunSpec({
    for (kind in listOf("absent", "zero-length", "sqlite-header")) {
        test("CLI creates and reopens baseline one from $kind database") {
            withDatabasePath { path ->
                if (kind == "zero-length") Files.createFile(path)
                if (kind == "sqlite-header") {
                    sql(path, "PRAGMA user_version = 0")
                    (Files.size(path) > 0L) shouldBe true
                }
                repeat(2) {
                    withStore(path) { store, actual ->
                        actual shouldBe path
                        store.database.schemaQueries.selectPersonById("absent").executeAsOneOrNull() shouldBe null
                    }
                    version(path) shouldBe 1
                }
            }
        }
    }
    test("CLI refuses a newer populated database before calling the action or changing bytes") {
        withDatabasePath { path ->
            withStore(path) { _, _ -> }
            sql(path, "INSERT INTO person VALUES ('retained', '2026-11-26T00:00:00Z')")
            sql(path, "PRAGMA user_version = 2")
            val before = Files.readAllBytes(path)
            var called = false
            shouldThrow<UnsupportedSchemaVersionException> { withStore(path) { _, _ -> called = true } }
            called shouldBe false
            Files.readAllBytes(path).toList() shouldBe before.toList()
        }
    }
    test("CLI recognizes core tables even when a trigger has the same name") {
        withDatabasePath { path ->
            withStore(path) { _, _ -> }
            sql(path, "CREATE TRIGGER person AFTER INSERT ON person BEGIN SELECT 1; END")
            withStore(path) { _, _ -> }
            version(path) shouldBe 1
        }
    }
    test("CLI refuses unknown versions even when the SQLite database has no tables") {
        withDatabasePath { path ->
            for (marker in listOf(-1, 2)) {
                sql(path, "PRAGMA user_version = $marker")
                val before = Files.readAllBytes(path)
                shouldThrow<UnsupportedSchemaVersionException> {
                    withStore(path) { _, _ -> error("Unexpected action") }
                }
                Files.readAllBytes(path).toList() shouldBe before.toList()
            }
        }
    }
    test("CLI refuses unrelated unversioned data and an empty versioned database unchanged") {
        withDatabasePath { path ->
            sql(path, "PRAGMA user_version = 1")
            assertInvalidLayout(path)
            sql(path, "PRAGMA user_version = 0")
            for (table in listOf("unrelated", "sqliteXdata")) {
                sql(path, "CREATE TABLE $table (payload TEXT)")
                sql(path, "INSERT INTO $table VALUES ('retained')")
                assertInvalidLayout(path)
                sql(path, "DROP TABLE $table")
            }
        }
    }
})

private fun assertInvalidLayout(path: Path) {
    val before = Files.readAllBytes(path)
    shouldThrow<IllegalArgumentException> { withStore(path) { _, _ -> error("Unexpected action") } }
    Files.readAllBytes(path).toList() shouldBe before.toList()
}

private fun withDatabasePath(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("sota-opening-")
    val path = directory.resolve("store.db")
    try { block(path) } finally {
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }
}

private fun sql(path: Path, statement: String) = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
    connection.createStatement().use { it.execute(statement) }
}

private fun version(path: Path) = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
    }
}
