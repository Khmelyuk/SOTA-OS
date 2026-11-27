package sotaos.test.migration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.persistence.SqlDelightStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class SqlDelightBaselineTest : FunSpec({
    test("frozen SQLDelight snapshot is empty healthy and exactly the declared baseline") {
        withSnapshot { path ->
            withConnection(path) { connection ->
                val runtimeGuards = baselineManifest().getValue("runtimeInstalledTriggerNames").jsonArray
                    .map { it.jsonPrimitive.content }.toSet()
                connection.baselineObjects() shouldBe baselineCatalog().filterNot {
                    it.first() == "trigger" && it[1] in runtimeGuards
                }
                connection.capture().values.forEach { it.rows shouldBe emptyList() }
                connection.query("PRAGMA user_version") shouldBe listOf(listOf("0"))
                connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
            }
        }
    }
    test("opening a snapshot copy preserves seeded data and installs the complete runtime baseline") {
        withSnapshot { path ->
            withConnection(path) { connection ->
                connection.createStatement().use {
                    it.execute("INSERT INTO person VALUES ('retained', '2026-11-27T00:00:00Z')")
                }
            }
            repeat(3) {
                JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                    SqlDelightStore(driver)
                }
                withConnection(path) { connection ->
                    connection.baselineObjects() shouldBe baselineCatalog()
                    connection.query("PRAGMA user_version") shouldBe listOf(listOf("1"))
                    connection.query("SELECT * FROM person") shouldBe
                        listOf(listOf("retained", "2026-11-27T00:00:00Z"))
                    connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                    connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
                }
            }
        }
    }
})

private fun Connection.baselineObjects() = query(
    "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY type, name"
)

private fun withConnection(path: Path, block: (Connection) -> Unit) =
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        block(connection)
    }

private fun withSnapshot(block: (Path) -> Unit) {
    val bytes = requireNotNull(SqlDelightBaselineTest::class.java
        .getResourceAsStream("/schema-baseline/sqldelight/1.db")).use { it.readBytes() }
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } shouldBe
        "b226482970ac8122046cc1b8f0d0a04e72f4beac2dec0fc0abbea0d6d849ca18"
    val path = Files.createTempFile("sota-declared-baseline-", ".db")
    try {
        Files.write(path, bytes)
        block(path)
    } finally {
        Files.deleteIfExists(path)
    }
}
