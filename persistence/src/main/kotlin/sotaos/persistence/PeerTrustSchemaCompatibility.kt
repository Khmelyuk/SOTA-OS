package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

/** Additive, idempotent upgrade for databases created before peer provisioning. */
internal fun createPeerTrustSchemaIfMissing(driver: SqlDriver) {
    driver.execute(null, """CREATE TABLE IF NOT EXISTS trusted_peer (
    peer_id TEXT NOT NULL PRIMARY KEY,
    policy_json TEXT NOT NULL,
    credential_hash TEXT NOT NULL UNIQUE,
    revision INTEGER NOT NULL,
    active INTEGER NOT NULL
)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS provisioning_audit (
    audit_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    target TEXT NOT NULL,
    operation TEXT NOT NULL,
    actor_kind TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    authority_id TEXT NOT NULL,
    purpose TEXT NOT NULL,
    occurred_at TEXT NOT NULL
)""", 0)
    for (operation in listOf("UPDATE", "DELETE")) {
        driver.execute(null, """CREATE TRIGGER IF NOT EXISTS provisioning_audit_no_${operation.lowercase()}
            BEFORE $operation ON provisioning_audit
            BEGIN SELECT RAISE(ABORT, 'provisioning audit is append-only'); END""", 0)
    }
}
