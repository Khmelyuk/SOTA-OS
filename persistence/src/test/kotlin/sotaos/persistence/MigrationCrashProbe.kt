package sotaos.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path

/** Disposable test child: halt bypasses finally/close and leaves SQLite to recover on reopening. */
fun main(args: Array<String>) {
    val driver = JdbcSqliteDriver("jdbc:sqlite:${Path.of(args[0])}")
    driver.sql("PRAGMA foreign_keys = ON")
    if (args[1] == "COMMITTED") {
        SqlDelightStore(driver, probePlan())
    } else {
        val interrupted = InterruptMigrationDriver(driver, MigrationBoundary.valueOf(args[1])) {
            Runtime.getRuntime().halt(73)
        }
        SqlDelightStore(interrupted, probePlan())
        error("Requested crash boundary was not reached")
    }
    Runtime.getRuntime().halt(73)
}
