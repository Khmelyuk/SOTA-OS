package sotaos.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import sotaos.persistence.db.SotaOsDatabase

/**
 * Trusted, build-time migration configuration, never supplied by CLI/database content.
 * Each source version needs explicit runtime/data work (an empty lambda is an intentional no-op).
 * The current installer must also work after fresh Schema.create, where no migration runs.
 */
internal class StoreMigrationPlan(
    val schema: SqlSchema<QueryResult.Value<Unit>> = SotaOsDatabase.Schema,
    val afterStep: Map<Long, (SqlDriver) -> Unit> = emptyMap(),
    val installCurrent: (SqlDriver) -> Unit = ::installBaselineRuntimeSchema,
    val validateCurrent: (SqlDriver) -> Unit = {}
) {
    init {
        require(schema.version in STORE_BASELINE_VERSION..Int.MAX_VALUE.toLong())
        require(afterStep.size.toLong() == schema.version - STORE_BASELINE_VERSION &&
            afterStep.keys.sorted().withIndex().all { (index, source) -> source == index + STORE_BASELINE_VERSION }) {
            "Every numbered schema step requires an explicit runtime migration registration."
        }
    }
}

/** One owner for creation, legacy adoption, numbered steps and the completion marker. */
internal fun initializeStoreSchema(driver: SqlDriver, plan: StoreMigrationPlan) {
    check(driver.currentTransaction() == null) { "Open the store outside an existing transaction." }
    val (previous, fresh) = inspectStoreSchema(driver, plan.schema.version)
    when {
        fresh -> createCurrentStore(driver, plan)
        else -> {
            var version = previous
            if (version == 0L) {
                // Preserve the established resumable, non-atomic legacy adoption boundary.
                installBaselineRuntimeSchema(driver)
                markStoreVersion(driver, STORE_BASELINE_VERSION)
                if (plan.schema.version == STORE_BASELINE_VERSION) return
                version = STORE_BASELINE_VERSION
            }
            if (version < plan.schema.version) {
                migrateStore(driver, version, plan)
            } else {
                plan.installCurrent(driver)
            }
        }
    }
}

private fun createCurrentStore(driver: SqlDriver, plan: StoreMigrationPlan) {
    storeSchemaTransaction(driver) {
        check(inspectStoreSchema(driver, plan.schema.version) == (0L to true)) { "SQLite opening state changed." }
        plan.schema.create(driver)
        finishCurrentStore(driver, plan)
    }
}

/** All pending steps, guards, validation and marker commit together, or all roll back. */
private fun migrateStore(driver: SqlDriver, previous: Long, plan: StoreMigrationPlan) {
    storeSchemaTransaction(driver) {
        check(readStoreVersion(driver) == previous) { "SQLite version changed; reopen with a fresh driver." }
        for (version in previous until plan.schema.version) {
            plan.schema.migrate(driver, version, version + 1)
            plan.afterStep.getValue(version)(driver)
        }
        finishCurrentStore(driver, plan)
    }
}

private fun finishCurrentStore(driver: SqlDriver, plan: StoreMigrationPlan) {
    plan.installCurrent(driver)
    inspectStoreSchema(driver, plan.schema.version)
    plan.validateCurrent(driver)
    val valid = driver.executeQuery(null, "PRAGMA foreign_key_check", { cursor ->
        QueryResult.Value(!cursor.next().value)
    }, 0).value
    check(valid) { "SQLite migration left invalid foreign keys; refusing completion." }
    markStoreVersion(driver, plan.schema.version)
}

/** SQLDelight 2.0.2 can leave its JDBC transaction active when COMMIT itself fails. */
private fun storeSchemaTransaction(driver: SqlDriver, block: () -> Unit) {
    runCatching {
        SotaOsDatabase(driver).transaction(noEnclosing = true) { block() }
    }.onFailure { failure ->
        val unfinished = driver.currentTransaction()
        if (unfinished != null) {
            runCatching { driver.execute(null, "ROLLBACK TRANSACTION", 0) }
                .onFailure { if (it !== failure) failure.addSuppressed(it) }
            // File-backed JdbcSqliteDriver.close() is a no-op; close the stranded connection.
            runCatching { (unfinished as? ConnectionManager.Transaction)?.connection?.close() }
                .onFailure { if (it !== failure) failure.addSuppressed(it) }
        }
    }.getOrThrow()
}
