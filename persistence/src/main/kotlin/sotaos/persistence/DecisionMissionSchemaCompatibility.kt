package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

/** No backfill: a current Mission cannot establish what a historical Decision approved. */
internal fun createDecisionMissionSnapshotSchema(driver: SqlDriver) {
    runCatching {
        driver.execute(null, "ALTER TABLE decision ADD COLUMN mission_snapshot_json TEXT", 0)
    }.onFailure { failure ->
        if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
    }
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS decision_mission_binding_immutable BEFORE UPDATE ON decision
WHEN OLD.mission_snapshot_json IS NOT NEW.mission_snapshot_json OR OLD.intent_ref != NEW.intent_ref
BEGIN SELECT RAISE(ABORT, 'decision mission binding is immutable; create a new decision'); END""", 0)
}
