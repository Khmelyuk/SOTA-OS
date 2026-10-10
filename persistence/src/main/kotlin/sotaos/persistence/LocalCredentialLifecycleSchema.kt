package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

/** Additive migration: preserve verifiers, bindings, failures and lockout for existing databases. */
internal fun createLocalCredentialLifecycleSchema(driver: SqlDriver) {
    listOf("revision INTEGER NOT NULL DEFAULT 1", "revoked_at TEXT").forEach { column ->
        runCatching { driver.execute(null, "ALTER TABLE local_credential ADD COLUMN $column", 0) }
            .onFailure { if (!it.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw it }
    }
    driver.execute(null, """CREATE TABLE IF NOT EXISTS local_credential_audit (
        audit_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
        unit_id TEXT NOT NULL, login_handle TEXT NOT NULL, revision INTEGER NOT NULL,
        operation TEXT NOT NULL, actor_person_id TEXT, occurred_at TEXT NOT NULL,
        FOREIGN KEY(unit_id, login_handle) REFERENCES local_credential(unit_id, login_handle))""", 0)
    listOf("update", "delete").forEach { operation ->
        driver.execute(null, """CREATE TRIGGER IF NOT EXISTS credential_audit_no_$operation
            BEFORE $operation ON local_credential_audit
            BEGIN SELECT RAISE(ABORT, 'credential audit is append-only'); END""", 0)
    }
    // Baseline records denote imported state, not proof of a historical operator action.
    driver.execute(null, """INSERT INTO local_credential_audit
        (unit_id, login_handle, revision, operation, actor_person_id, occurred_at)
        SELECT unit_id, login_handle, revision, 'LEGACY_BASELINE', NULL, created_at FROM local_credential c
        WHERE NOT EXISTS (SELECT 1 FROM local_credential_audit a
            WHERE a.unit_id = c.unit_id AND a.login_handle = c.login_handle)""", 0)
}
