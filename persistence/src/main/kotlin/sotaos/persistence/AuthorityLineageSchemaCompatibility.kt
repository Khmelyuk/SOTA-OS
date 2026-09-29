package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

internal fun createAuthorityLineageSchemaIfMissing(driver: SqlDriver) {
    runCatching {
        driver.execute(null, "ALTER TABLE authority ADD COLUMN parent_authority_id " +
            "TEXT REFERENCES authority(authority_id)", 0)
    }.onFailure { failure ->
        if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
    }
    driver.execute(null, """CREATE TABLE IF NOT EXISTS authority_revocation (
 revocation_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
 authority_id TEXT NOT NULL REFERENCES authority(authority_id),
 cascade_root TEXT NOT NULL REFERENCES authority(authority_id),
 actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL,
 reason TEXT NOT NULL, occurred_at TEXT NOT NULL
)""", 0)
    driver.execute(null, """CREATE INDEX IF NOT EXISTS authority_parent_idx ON authority(parent_authority_id)""", 0)
    authorityLineageGuards.forEach { driver.execute(null, it, 0) }
}

private val authorityLineageGuards = listOf(
    """CREATE TRIGGER IF NOT EXISTS authority_parent_immutable BEFORE UPDATE ON authority
WHEN OLD.parent_authority_id IS NOT NEW.parent_authority_id
BEGIN SELECT RAISE(ABORT, 'authority parent is immutable'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_parent_binding BEFORE INSERT ON authority
WHEN NEW.parent_authority_id IS NOT NULL AND (
 NEW.parent_authority_id = NEW.authority_id OR NOT EXISTS (
  SELECT 1 FROM authority p WHERE p.authority_id = NEW.parent_authority_id
  AND p.subject_kind = NEW.issuer_kind AND p.subject_id = NEW.issuer_id
  AND p.accountable_kind = NEW.accountable_kind AND p.accountable_id = NEW.accountable_id
  AND p.context_json = NEW.context_json AND p.state = 'ACTIVE'))
BEGIN SELECT RAISE(ABORT, 'invalid delegation parent'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_lineage_terms_immutable BEFORE UPDATE ON authority
WHEN (OLD.parent_authority_id IS NOT NULL OR EXISTS (
 SELECT 1 FROM authority child WHERE child.parent_authority_id = OLD.authority_id))
AND (OLD.issuer_kind != NEW.issuer_kind OR OLD.issuer_id != NEW.issuer_id
 OR OLD.subject_kind != NEW.subject_kind OR OLD.subject_id != NEW.subject_id
 OR OLD.accountable_kind != NEW.accountable_kind OR OLD.accountable_id != NEW.accountable_id
 OR OLD.context_json != NEW.context_json OR OLD.scope_json != NEW.scope_json
 OR OLD.valid_from != NEW.valid_from OR OLD.valid_until IS NOT NEW.valid_until)
BEGIN SELECT RAISE(ABORT, 'delegation terms are immutable; revoke and issue a new grant'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_ancestor_exit_insert BEFORE INSERT ON authority
WHEN NEW.parent_authority_id IS NOT NULL
AND NEW.state NOT IN ('REVOKED','COMPLETED','REJECTED','ABORTED','FAILED')
AND EXISTS (
 WITH RECURSIVE ancestors(authority_id, parent_authority_id, issuer_kind, issuer_id, subject_kind, subject_id) AS (
  SELECT authority_id, parent_authority_id, issuer_kind, issuer_id, subject_kind, subject_id
  FROM authority WHERE authority_id = NEW.parent_authority_id
  UNION
  SELECT p.authority_id, p.parent_authority_id, p.issuer_kind, p.issuer_id, p.subject_kind, p.subject_id
  FROM authority p JOIN ancestors a ON p.authority_id = a.parent_authority_id
 )
 SELECT 1 FROM ancestors a JOIN exit_process e ON e.stage != 'COMPLETED'
 WHERE NEW.accountable_kind = 'CORE' AND e.core_id = NEW.accountable_id
 AND ((a.issuer_kind = 'PERSON' AND a.issuer_id = e.person_id)
 OR (a.subject_kind = 'PERSON' AND a.subject_id = e.person_id)))
BEGIN SELECT RAISE(ABORT, 'ancestor is exiting this Core'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_ancestor_exit_update BEFORE UPDATE ON authority
WHEN NEW.parent_authority_id IS NOT NULL
AND NEW.state NOT IN ('REVOKED','COMPLETED','REJECTED','ABORTED','FAILED')
AND EXISTS (
 WITH RECURSIVE ancestors(authority_id, parent_authority_id, issuer_kind, issuer_id, subject_kind, subject_id) AS (
  SELECT authority_id, parent_authority_id, issuer_kind, issuer_id, subject_kind, subject_id
  FROM authority WHERE authority_id = NEW.parent_authority_id
  UNION
  SELECT p.authority_id, p.parent_authority_id, p.issuer_kind, p.issuer_id, p.subject_kind, p.subject_id
  FROM authority p JOIN ancestors a ON p.authority_id = a.parent_authority_id
 )
 SELECT 1 FROM ancestors a JOIN exit_process e ON e.stage != 'COMPLETED'
 WHERE NEW.accountable_kind = 'CORE' AND e.core_id = NEW.accountable_id
 AND ((a.issuer_kind = 'PERSON' AND a.issuer_id = e.person_id)
 OR (a.subject_kind = 'PERSON' AND a.subject_id = e.person_id)))
BEGIN SELECT RAISE(ABORT, 'ancestor is exiting this Core'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_revocation_no_update BEFORE UPDATE ON authority_revocation
BEGIN SELECT RAISE(ABORT, 'authority revocation audit is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_revocation_no_delete BEFORE DELETE ON authority_revocation
BEGIN SELECT RAISE(ABORT, 'authority revocation audit is append-only'); END""")
