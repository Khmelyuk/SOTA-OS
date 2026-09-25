package sotaos.api.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files
import java.nio.file.Path

private fun defaultDatabasePath(): Path {
    val xdgDataHome = System.getenv("XDG_DATA_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
    val dataHome = xdgDataHome ?: Path.of(System.getProperty("user.home"), ".local", "share")
    return dataHome.resolve("sota-os").resolve("sota-os.db").toAbsolutePath().normalize()
}

internal fun <T> withStore(
    requestedPath: Path?,
    action: (sotaos.persistence.SqlDelightStore, Path) -> T
): T {
    val path = requestedPath ?: defaultDatabasePath()
    Files.createDirectories(path.parent)
    val shouldCreateSchema = !Files.exists(path) || Files.size(path) == 0L
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    return driver.use {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        if (shouldCreateSchema) SotaOsDatabase.Schema.create(driver)
        action(sotaos.persistence.SqlDelightStore(driver), path)
    }
}
