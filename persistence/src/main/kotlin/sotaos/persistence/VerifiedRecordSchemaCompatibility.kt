package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

internal fun createVerifiedRecordSchemaIfMissing(driver: SqlDriver) {
    driver.execute(null, """CREATE TABLE IF NOT EXISTS verified_sync_record (
 event_id TEXT NOT NULL PRIMARY KEY REFERENCES event(event_id),
 canonical_record TEXT NOT NULL,
 actor_kind TEXT NOT NULL,
 actor_id TEXT NOT NULL,
 key_id TEXT NOT NULL,
 public_key_base64 TEXT NOT NULL,
 verified_at TEXT NOT NULL
);""", 0)
    for (operation in listOf("UPDATE", "DELETE")) {
        driver.execute(null, """CREATE TRIGGER IF NOT EXISTS verified_record_no_${operation.lowercase()}
            BEFORE $operation ON verified_sync_record
            BEGIN SELECT RAISE(ABORT, 'verification evidence is append-only'); END""", 0)
    }
}
