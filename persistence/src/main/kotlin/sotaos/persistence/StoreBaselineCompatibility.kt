package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

/** Frozen legacy-to-one compatibility sequence; preserve its idempotent retry behavior. */
internal fun installBaselineRuntimeSchema(driver: SqlDriver) {
    createSyncSchemaIfMissing(driver)
    createPeerTrustSchemaIfMissing(driver)
    createExitSchemaIfMissing(driver)
    createRelationInventorySchemaIfMissing(driver)
    createAuthorityLineageSchemaIfMissing(driver)
    createVerifiedRecordSchemaIfMissing(driver)
    createHistoricalApprovalSchemaIfMissing(driver)
    createDecisionMissionSnapshotSchema(driver)
    // Add the structured affected-person set to older decision tables.
    runCatching {
        driver.execute(null, "ALTER TABLE decision ADD COLUMN affected_persons_json TEXT NOT NULL DEFAULT '[]'", 0)
    }.onFailure { failure ->
        if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
    }
    runCatching {
        driver.execute(null,
            "ALTER TABLE decision ADD COLUMN purpose TEXT NOT NULL DEFAULT 'Execute mission action'", 0)
    }.onFailure { failure ->
        if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
    }
    createRightsAndCredentialBaseline(driver)
}

private fun createRightsAndCredentialBaseline(driver: SqlDriver) {
    // Additive compatibility migration for databases created before Rights/Consent were persisted.
    driver.execute(null, """CREATE TABLE IF NOT EXISTS right_record (
            right_id TEXT NOT NULL PRIMARY KEY, subject_kind TEXT NOT NULL, subject_id TEXT NOT NULL,
            right_type TEXT NOT NULL, scope_json TEXT NOT NULL, source TEXT NOT NULL,
            status TEXT NOT NULL, constraints_json TEXT NOT NULL)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS consent (
            consent_id TEXT NOT NULL PRIMARY KEY, subject_person_id TEXT NOT NULL,
            recipient_kind TEXT NOT NULL, recipient_id TEXT NOT NULL, purpose TEXT NOT NULL,
            context_json TEXT NOT NULL, scope_json TEXT NOT NULL, notice TEXT NOT NULL,
            granted_at TEXT NOT NULL, valid_until TEXT, revoked_at TEXT,
            FOREIGN KEY (subject_person_id) REFERENCES person(person_id))""", 0)
    runCatching {
        driver.execute(null, "ALTER TABLE consent ADD COLUMN affirmation TEXT NOT NULL DEFAULT 'legacy-record'", 0)
    }.onFailure { failure ->
        if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
    }
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS consent_no_unrevoke
            BEFORE UPDATE OF revoked_at ON consent
            WHEN OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS NULL
            BEGIN SELECT RAISE(ABORT, 'consent revocation is irreversible'); END""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS authentication_binding (
            unit_id TEXT NOT NULL, provider_id TEXT NOT NULL, provider_subject TEXT NOT NULL,
            person_id TEXT NOT NULL, linked_at TEXT NOT NULL,
            PRIMARY KEY(unit_id, provider_id, provider_subject),
            FOREIGN KEY(person_id) REFERENCES person(person_id))""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS local_credential (
            unit_id TEXT NOT NULL, provider_id TEXT NOT NULL DEFAULT 'local-passphrase',
            login_handle TEXT NOT NULL, salt_base64 TEXT NOT NULL, hash_base64 TEXT NOT NULL,
            iterations INTEGER NOT NULL, failed_attempts INTEGER NOT NULL DEFAULT 0,
            locked_until TEXT, created_at TEXT NOT NULL,
            PRIMARY KEY(unit_id, login_handle),
            FOREIGN KEY(unit_id, provider_id, login_handle)
                REFERENCES authentication_binding(unit_id, provider_id, provider_subject))""", 0)
    createLocalCredentialLifecycleSchema(driver)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS actor_signing_key (
            actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL, key_id TEXT NOT NULL,
            public_key_base64 TEXT NOT NULL, PRIMARY KEY (actor_kind, actor_id))""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS actor_signing_key_audit (
            audit_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL, key_id TEXT NOT NULL,
            operation TEXT NOT NULL, public_key_base64 TEXT, occurred_at TEXT NOT NULL)""", 0)
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS actor_signing_key_audit_no_update
            BEFORE UPDATE ON actor_signing_key_audit
            BEGIN SELECT RAISE(ABORT, 'actor signing key audit is append-only'); END""", 0)
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS actor_signing_key_audit_no_delete
            BEFORE DELETE ON actor_signing_key_audit
            BEGIN SELECT RAISE(ABORT, 'actor signing key audit is append-only'); END""", 0)
}
