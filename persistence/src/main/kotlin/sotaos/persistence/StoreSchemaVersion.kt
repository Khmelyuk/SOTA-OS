package sotaos.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import sotaos.persistence.db.SotaOsDatabase

/** Legacy compatibility completes at one, independently of future numbered upgrades. */
internal const val STORE_BASELINE_VERSION = 1L

class UnsupportedSchemaVersionException(val version: Long) : IllegalStateException(
    "Unsupported SQLite schema version $version; this build accepts legacy 0 and versions " +
        "$STORE_BASELINE_VERSION through ${SotaOsDatabase.Schema.version}."
)

internal fun readStoreVersion(driver: SqlDriver): Long = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
    check(cursor.next().value)
    QueryResult.Value(requireNotNull(cursor.getLong(0)))
}, 0).value

/** Version rejection and structural preflight never write to the database. */
internal fun inspectStoreSchema(driver: SqlDriver, targetVersion: Long): Pair<Long, Boolean> {
    val version = readStoreVersion(driver)
    if (version !in 0L..targetVersion) throw UnsupportedSchemaVersionException(version)
    val objects = driver.executeQuery(null,
        "SELECT type, name FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'", { cursor ->
            val result = mutableSetOf<Pair<String, String>>()
            while (cursor.next().value) result += requireNotNull(cursor.getString(0)) to
                requireNotNull(cursor.getString(1))
            QueryResult.Value(result)
        }, 0).value
    val fresh = objects.isEmpty() && version == 0L
    if (!fresh) {
        val coreTables = listOf("person", "identity", "core", "membership", "authority", "mission",
            "decision", "action", "result", "event", "experience", "knowledge")
        require(coreTables.all { ("table" to it) in objects }) {
            "SQLite database lacks required SOTA core tables; refusing schema initialization."
        }
    }
    return version to fresh
}

internal fun markStoreVersion(driver: SqlDriver, version: Long) {
    driver.execute(null, "PRAGMA user_version = $version", 0)
}
