package sotaos.test.migration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class CanonicalSchemaTest : FunSpec({
    test("generated declarations plus the documented runtime delta equal the pinned baseline") {
        withCanonicalDatabase { driver, connection ->
            SotaOsDatabase.Schema.version shouldBe 1L
            SotaOsDatabase.Schema.create(driver)
            val runtimeNames = baselineManifest().getValue("runtimeInstalledTriggerNames").jsonArray
                .map { it.jsonPrimitive.content }.toSet()
            runtimeNames.size shouldBe 41
            connection.catalog() shouldBe baselineCatalog().filterNot {
                it.first() == "trigger" && it[1] in runtimeNames
            }
            connection.query("PRAGMA user_version") shouldBe listOf(listOf("0"))
            SqlDelightStore(driver).use {
                connection.catalog() shouldBe baselineCatalog()
                connection.query("PRAGMA user_version") shouldBe listOf(listOf("1"))
            }
        }
    }
    test("direct fresh opening and repeated initialization preserve the exact baseline catalog") {
        withCanonicalDatabase { driver, connection ->
            repeat(3) {
                SqlDelightStore(driver)
                connection.catalog() shouldBe baselineCatalog()
                connection.query("PRAGMA integrity_check") shouldBe listOf(listOf("ok"))
                connection.query("PRAGMA foreign_key_check") shouldBe emptyList()
            }
        }
    }
    test("complete runtime baseline enforces signing key audit immutability") {
        withCanonicalDatabase { driver, connection ->
            SotaOsDatabase.Schema.create(driver)
            SqlDelightStore(driver)
            connection.createStatement().use { statement ->
                statement.execute("""INSERT INTO actor_signing_key_audit
                    (actor_kind, actor_id, key_id, operation, public_key_base64, occurred_at)
                    VALUES ('PERSON', 'actor', 'key', 'ENROLL', 'public-test-vector', '2026-11-27T00:00:00Z')""")
                val before = connection.query("SELECT * FROM actor_signing_key_audit")
                shouldThrow<Exception> { statement.execute("UPDATE actor_signing_key_audit SET key_id = 'changed'") }
                shouldThrow<Exception> { statement.execute("DELETE FROM actor_signing_key_audit") }
                connection.query("SELECT * FROM actor_signing_key_audit") shouldBe before
            }
        }
    }
    for (schema in HistoricalSchema.entries) {
        test("${schema.directory} upgrade retains the canonical indexes and all guard definitions") {
            HistoricalFixture(schema).use { fixture ->
                fixture.open()
                repeat(3) {
                    fixture.reopen()
                    val actual = fixture.sql { it.catalog() }
                    actual.map { it.take(3) } shouldBe baselineCatalog().map { it.take(3) }
                    val expected = historicalCatalog(schema).filter { it.first() != "table" }
                    actual.filter { it.first() != "table" }.zip(expected).forEach { (row, baseline) ->
                        row shouldBe baseline
                    }
                }
            }
        }
    }
})

private fun baselineManifest(): JsonObject {
    val manifest = pinnedManifest("v1.json",
        "0d3728ef8e8df4e687509e8da68cf1810f0f2c80137af69282fb740fc78dae26"
    )
    manifest.getValue("sourceRevision").jsonPrimitive.content shouldBe "430bde9a19c3690e843a5ecbe6f54e3c3fd6563c"
    manifest.getValue("schemaVersion").jsonPrimitive.int shouldBe 1
    return manifest
}

private fun pinnedManifest(name: String, hash: String): JsonObject {
    val bytes = requireNotNull(CanonicalSchemaTest::class.java.getResourceAsStream("/schema-baseline/$name"))
        .use { it.readBytes() }
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } shouldBe hash
    return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

private fun historicalCatalog(schema: HistoricalSchema): List<List<String?>> {
    val manifest = pinnedManifest("historical-ddl-variants.json",
        "094610d34f28e5b2cd86c5e3a2d9fac3460e8c10b088e77b18dfd90e92346677")
    val variants = manifest.getValue("profiles").jsonObject.getValue(schema.directory).jsonObject
    return baselineCatalog().map { row ->
        variants[row[1]]?.jsonPrimitive?.content?.let { row.take(3) + it } ?: row
    }
}

private fun baselineCatalog(): List<List<String?>> = baselineManifest().getValue("catalog").jsonArray
    .map { row -> row.jsonArray.map { it.jsonPrimitive.contentOrNull } }

private fun Connection.catalog() = query(
    "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY type, name"
)

private fun withCanonicalDatabase(block: (JdbcSqliteDriver, Connection) -> Unit) {
    val path = Files.createTempFile("sota-canonical-", ".db")
    try {
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            DriverManager.getConnection("jdbc:sqlite:$path").use { block(driver, it) }
        }
    } finally {
        Files.deleteIfExists(path)
    }
}
