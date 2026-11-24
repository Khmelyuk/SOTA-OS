package sotaos.test.migration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.*
import sotaos.persistence.SqlDelightStore
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

internal enum class HistoricalSchema(val directory: String, val revision: String,
    val schemaHash: String, val dataHash: String) {
    DURABLE_P09("durable-p09", "ece3ad113fef41be69e632117304e28bfca02708",
        "649ff7b4d82022f596db486589a8e20bf6ee9d7828ecf2eaa5e8721c24efba9a",
        "21d8fcc118d3fdd609819f48b08f032ad4e02c951483c7b07f72abf015a6cb3d"),
    KNOWLEDGE_VERSIONS("knowledge-versions", "3c1a4aa1d20cf77179e3c37580ddd682b329d018",
        "b001a70f649e388c888b5d6515b4ddd96eca9442ffacea66b8967bbdc060e0a2",
        "51732f8a5e9a3a2776e5543ab59df49ac20045307b8fbdad4543a6ee5f4be362"),
    RUNTIME_SIGNED("runtime-signed", "3c1a4aa1d20cf77179e3c37580ddd682b329d018",
        "74766c02dc2b391b7c8ca9d1b67c8d734749fc45f306eeeb2dbd269724cd407c",
        "6bf6b014e8c83d51fbe2e2d42a8a15ef651fb30435ac7d3e3e637ca4abbe572a"),
    RUNTIME_P10("runtime-p10", "3c1a4aa1d20cf77179e3c37580ddd682b329d018",
        "16014f87d9fe009b7e29e69eed8b4ef3d00cb6f7614212e6a2a19d338465fc3e",
        "64be74e2a862aa72e80f42c9289cbb72475947c2dbed0e88e625724615a7731c")
}

internal val declaredSchemas = listOf(HistoricalSchema.DURABLE_P09, HistoricalSchema.KNOWLEDGE_VERSIONS)

/** Builds only frozen historical DDL and raw synthetic rows, before current migration code can run. */
internal class HistoricalFixture(val schema: HistoricalSchema) : AutoCloseable {
    val path = Files.createTempFile("sota-historical-", ".db")
    private var opened: SqlDelightStore? = null

    init {
        Class.forName("org.sqlite.JDBC")
        val ddl = resource("schema.json", schema.schemaHash)
        require(ddl.getValue("revision").jsonPrimitive.content == schema.revision)
        val data = resource("data.json", schema.dataHash)
        require(data.getValue("fixtureOnly").jsonPrimitive.boolean)
        sql { it.restoreSnapshot(ddl, data) }
    }

    fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        block(connection)
    }

    fun execute(statement: String) = sql { connection ->
        connection.createStatement().use { it.execute(statement) }
    }

    fun open(): SqlDelightStore {
        check(opened == null)
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return try {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            SqlDelightStore(driver).also { opened = it }
        } catch (failure: Throwable) {
            driver.close()
            throw failure
        }
    }

    fun reopen(): SqlDelightStore {
        opened?.close()
        opened = null
        return open()
    }

    override fun close() {
        opened?.close()
        Files.deleteIfExists(path)
    }

    fun resource(name: String, expectedHash: String): JsonObject {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/migrations/${schema.directory}/$name"))
            .use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == expectedHash) { "Frozen fixture checksum mismatch: ${schema.directory}/$name" }
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
}

/** Restore final historical rows before guards that would correctly deny replaying past inserts today. */
private fun Connection.restoreSnapshot(ddl: JsonObject, data: JsonObject) {
    val (guards, structures) = ddl.getValue("statements").jsonArray.map { it.jsonPrimitive.content }
        .partition { it.trimStart().startsWith("CREATE TRIGGER", ignoreCase = true) }
    autoCommit = false
    try {
        createStatement().use { it.execute("PRAGMA defer_foreign_keys = ON") }
        structures.forEach { sql -> createStatement().use { it.execute(sql) } }
        data.getValue("rows").jsonArray.forEach { seed(it.jsonObject) }
        guards.forEach { sql -> createStatement().use { it.execute(sql) } }
        commit() // Foreign keys remain enabled; deferred references must be valid before testing starts.
    } catch (failure: Throwable) {
        rollback()
        throw failure
    } finally {
        autoCommit = true
    }
}

private fun Connection.seed(row: JsonObject) {
    val table = identifier(row.getValue("table").jsonPrimitive.content)
    val values = row.getValue("values").jsonObject
    val columns = values.keys.joinToString(",", transform = ::identifier)
    val parameters = values.keys.joinToString(",") { "?" }
    prepareStatement("INSERT INTO $table ($columns) VALUES ($parameters)").use { statement ->
        values.values.forEachIndexed { index, value ->
            when {
                value == JsonNull -> statement.setNull(index + 1, Types.NULL)
                value.jsonPrimitive.isString -> statement.setString(index + 1, value.jsonPrimitive.content)
                else -> statement.setLong(index + 1, value.jsonPrimitive.long)
            }
        }
        statement.executeUpdate()
    }
}
