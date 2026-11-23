package sotaos.test.migration

import java.sql.Connection

internal data class HistoricalRows(val columns: List<String>, val rows: List<List<String?>>)
internal data class TableShape(val columns: List<List<String?>>, val foreignKeys: List<List<String?>>)

internal fun Connection.query(sql: String): List<List<String?>> = createStatement().use { statement ->
    statement.executeQuery(sql).use { result ->
        buildList {
            while (result.next()) add((1..result.metaData.columnCount).map(result::getString))
        }
    }
}

internal fun Connection.tableNames(): List<String> = query(
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
).map { requireNotNull(it.first()) }

internal fun identifier(value: String): String {
    require(value.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*"))) { "Fixture identifier is invalid." }
    return "\"$value\""
}

internal fun Connection.rows(table: String, columns: List<String>): List<List<String?>> {
    val projection = columns.joinToString(",", transform = ::identifier)
    return query("SELECT $projection FROM ${identifier(table)} ORDER BY $projection")
}

internal fun Connection.capture(): Map<String, HistoricalRows> = tableNames().associateWith { table ->
    val columns = query("PRAGMA table_info(${identifier(table)})").map { requireNotNull(it[1]) }
    HistoricalRows(columns, rows(table, columns))
}

internal fun Connection.schemaShape(): Map<String, TableShape> = tableNames().associateWith { table ->
    TableShape(query("PRAGMA table_info(${identifier(table)})").map { it.drop(1) }.sortedBy { it.first() },
        query("PRAGMA foreign_key_list(${identifier(table)})").map { it.drop(2) }.sortedBy { it.toString() })
}
