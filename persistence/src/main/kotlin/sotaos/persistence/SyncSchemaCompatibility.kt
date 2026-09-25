package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

/** Additive migration for existing pre-P09 databases; fresh databases use Sync.sq. */
internal fun createSyncSchemaIfMissing(driver: SqlDriver) {
    driver.execute(null, """CREATE TABLE IF NOT EXISTS sync_journal (
        position INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
        event_id TEXT NOT NULL UNIQUE REFERENCES event(event_id), record_json TEXT NOT NULL)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS sync_checkpoint (
        peer_id TEXT NOT NULL PRIMARY KEY, sent INTEGER NOT NULL, received INTEGER NOT NULL)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS sync_conflict (
        entity_kind TEXT NOT NULL, entity_id TEXT NOT NULL,
        left_event_id TEXT NOT NULL REFERENCES event(event_id),
        right_event_id TEXT NOT NULL REFERENCES event(event_id),
        PRIMARY KEY(entity_kind, entity_id, left_event_id, right_event_id))""", 0)
    for (table in listOf("sync_journal", "sync_conflict")) {
        for (operation in listOf("UPDATE", "DELETE")) {
            driver.execute(null, """CREATE TRIGGER IF NOT EXISTS ${table}_no_${operation.lowercase()}
                BEFORE $operation ON $table
                BEGIN SELECT RAISE(ABORT, 'sync history is append-only'); END""", 0)
        }
    }
}
