package sotaos.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import sotaos.persistence.db.SotaOsDatabase

/** Application SQLite baseline, including runtime-installed compatibility structures. */
internal const val STORE_SCHEMA_VERSION = 1L

class UnsupportedSchemaVersionException(val version: Long) : IllegalStateException(
    "Unsupported SQLite schema version $version; this build accepts legacy 0 and baseline $STORE_SCHEMA_VERSION."
)

/** Inspect the version before any schema writes; only an empty unversioned database is new. */
internal fun prepareStoreSchema(driver: SqlDriver): Long {
    val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
        check(cursor.next().value)
        QueryResult.Value(requireNotNull(cursor.getLong(0)))
    }, 0).value
    if (version != 0L && version != STORE_SCHEMA_VERSION) throw UnsupportedSchemaVersionException(version)
    val objects = driver.executeQuery(null,
        "SELECT type, name FROM sqlite_master WHERE name NOT GLOB 'sqlite_*'", { cursor ->
            val result = mutableSetOf<Pair<String, String>>()
            while (cursor.next().value) result += requireNotNull(cursor.getString(0)) to
                requireNotNull(cursor.getString(1))
            QueryResult.Value(result)
        }, 0).value
    if (objects.isEmpty() && version == 0L) {
        SotaOsDatabase(driver).transaction { SotaOsDatabase.Schema.create(driver) }
    } else {
        val coreTables = listOf("person", "identity", "core", "membership", "authority", "mission",
            "decision", "action", "result", "event", "experience", "knowledge")
        require(coreTables.all { ("table" to it) in objects }) {
            "SQLite database lacks required SOTA core tables; refusing schema initialization."
        }
    }
    return version
}

/** Mark completion only after every compatibility step succeeds; do not rewrite on ordinary reopen. */
internal fun finishStoreSchema(driver: SqlDriver, previousVersion: Long) {
    if (previousVersion == 0L) driver.execute(null, "PRAGMA user_version = $STORE_SCHEMA_VERSION", 0)
}
