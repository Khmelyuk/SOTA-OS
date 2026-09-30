package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

internal fun createHistoricalApprovalSchemaIfMissing(driver: SqlDriver) {
    driver.execute(null, """CREATE TABLE IF NOT EXISTS historical_record_approval (
 event_id TEXT NOT NULL PRIMARY KEY, canonical_record TEXT NOT NULL,
 actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL, key_id TEXT NOT NULL, public_key_base64 TEXT NOT NULL,
 target TEXT NOT NULL, operator_kind TEXT NOT NULL, operator_id TEXT NOT NULL, authority_id TEXT NOT NULL,
 purpose TEXT NOT NULL, approved_at TEXT NOT NULL, evidence_reference TEXT NOT NULL)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS historical_record_revocation (
 event_id TEXT NOT NULL PRIMARY KEY REFERENCES historical_record_approval(event_id),
 target TEXT NOT NULL, operator_kind TEXT NOT NULL, operator_id TEXT NOT NULL, authority_id TEXT NOT NULL,
 purpose TEXT NOT NULL, revoked_at TEXT NOT NULL)""", 0)
    for (table in listOf("historical_record_approval", "historical_record_revocation")) {
        for (operation in listOf("UPDATE", "DELETE")) {
            driver.execute(null, """CREATE TRIGGER IF NOT EXISTS ${table}_no_${operation.lowercase()}
BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'historical governance is append-only'); END""", 0)
        }
    }
    runCatching {
        driver.execute(null, "ALTER TABLE verified_sync_record ADD COLUMN historical_approval_target TEXT", 0)
    }.onFailure { failure ->
        if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
    }
}
