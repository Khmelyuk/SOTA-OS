package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

internal fun createExitSchemaIfMissing(driver: SqlDriver) {
    exitTables.forEach { driver.execute(null, it, 0) }
    exitHistoryGuards.forEach { driver.execute(null, it, 0) }
    exitCommitmentGuards.forEach { driver.execute(null, it, 0) }
}

private val exitTables = listOf(
    """CREATE TABLE IF NOT EXISTS exit_process (
 exit_id TEXT NOT NULL PRIMARY KEY, person_id TEXT NOT NULL REFERENCES person(person_id),
 core_id TEXT NOT NULL REFERENCES core(core_id), requested_at TEXT NOT NULL,
 updated_at TEXT NOT NULL, stage TEXT NOT NULL)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS exit_one_open ON exit_process(person_id, core_id)
WHERE stage != 'COMPLETED' """,
    """CREATE TABLE IF NOT EXISTS exit_transition (
 exit_id TEXT NOT NULL REFERENCES exit_process(exit_id), stage TEXT NOT NULL,
 actor_id TEXT NOT NULL, occurred_at TEXT NOT NULL, PRIMARY KEY(exit_id, stage))""",
    """CREATE TABLE IF NOT EXISTS exit_archive (
 exit_id TEXT NOT NULL PRIMARY KEY REFERENCES exit_process(exit_id),
 document_json TEXT NOT NULL, sha256 TEXT NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS core_exit_relation (
 relation_id TEXT NOT NULL PRIMARY KEY, person_id TEXT NOT NULL REFERENCES person(person_id),
 core_id TEXT NOT NULL REFERENCES core(core_id), kind TEXT NOT NULL, description TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('ACTIVE','CLOSED')))""",
    """CREATE TABLE IF NOT EXISTS exit_obligation (
 obligation_id TEXT NOT NULL PRIMARY KEY, person_id TEXT NOT NULL REFERENCES person(person_id),
 core_id TEXT NOT NULL REFERENCES core(core_id), description TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('OPEN','RETAINED','FULFILLED')), resolution TEXT, resolved_at TEXT)""",
    """CREATE TABLE IF NOT EXISTS exit_export_grant (
 person_id TEXT NOT NULL, core_id TEXT NOT NULL, kind TEXT NOT NULL, artifact_id TEXT NOT NULL,
 artifact_json TEXT NOT NULL, PRIMARY KEY(person_id, core_id, kind, artifact_id))""",
    """CREATE TABLE IF NOT EXISTS owned_exit_evidence (
 evidence_id TEXT NOT NULL PRIMARY KEY, person_id TEXT NOT NULL REFERENCES person(person_id),
 core_id TEXT NOT NULL REFERENCES core(core_id), description TEXT NOT NULL,
 source_event_id TEXT, recorded_at TEXT NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS exit_governance_audit (
 id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, person_id TEXT NOT NULL, core_id TEXT NOT NULL,
 operation TEXT NOT NULL, actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL,
 authority_id TEXT NOT NULL, evidence TEXT NOT NULL, occurred_at TEXT NOT NULL)""")

private val exitHistoryGuards = listOf(
    """CREATE TRIGGER IF NOT EXISTS exit_transition_no_update BEFORE UPDATE ON exit_transition
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_transition_no_delete BEFORE DELETE ON exit_transition
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_archive_no_update BEFORE UPDATE ON exit_archive
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_archive_no_delete BEFORE DELETE ON exit_archive
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_governance_audit_no_update BEFORE UPDATE ON exit_governance_audit
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_governance_audit_no_delete BEFORE DELETE ON exit_governance_audit
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS owned_exit_evidence_no_update BEFORE UPDATE ON owned_exit_evidence
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""",
    """CREATE TRIGGER IF NOT EXISTS owned_exit_evidence_no_delete BEFORE DELETE ON owned_exit_evidence
BEGIN SELECT RAISE(ABORT, 'exit history is append-only'); END""")

private val exitCommitmentGuards = listOf(
    """CREATE TRIGGER IF NOT EXISTS authority_exit_guard_insert BEFORE INSERT ON authority
WHEN NEW.state NOT IN ('REVOKED','COMPLETED','REJECTED','ABORTED','FAILED')
AND NEW.accountable_kind = 'CORE'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.accountable_id
AND ((NEW.subject_kind = 'PERSON'
AND e.person_id = NEW.subject_id)
OR (NEW.issuer_kind = 'PERSON'
AND e.person_id = NEW.issuer_id)))
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS authority_exit_guard_update BEFORE UPDATE ON authority
WHEN NEW.state NOT IN ('REVOKED','COMPLETED','REJECTED','ABORTED','FAILED')
AND NEW.accountable_kind = 'CORE'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.accountable_id
AND ((NEW.subject_kind = 'PERSON'
AND e.person_id = NEW.subject_id)
OR (NEW.issuer_kind = 'PERSON'
AND e.person_id = NEW.issuer_id)))
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS membership_exit_guard_insert BEFORE INSERT ON membership
WHEN NEW.state = 'ACTIVE'
AND NEW.collective_kind = 'CORE'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.collective_id
AND e.person_id = NEW.person_id)
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS membership_exit_guard_update BEFORE UPDATE ON membership
WHEN NEW.state = 'ACTIVE'
AND NEW.collective_kind = 'CORE'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.collective_id
AND e.person_id = NEW.person_id)
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS core_exit_relation_exit_guard_insert BEFORE INSERT ON core_exit_relation
WHEN NEW.state = 'ACTIVE'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.core_id
AND e.person_id = NEW.person_id)
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS core_exit_relation_exit_guard_update BEFORE UPDATE ON core_exit_relation
WHEN NEW.state = 'ACTIVE'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.core_id
AND e.person_id = NEW.person_id)
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_obligation_exit_guard_insert BEFORE INSERT ON exit_obligation
WHEN NEW.state = 'OPEN'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.core_id
AND e.person_id = NEW.person_id)
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""",
    """CREATE TRIGGER IF NOT EXISTS exit_obligation_exit_guard_update BEFORE UPDATE ON exit_obligation
WHEN NEW.state = 'OPEN'
AND EXISTS (SELECT 1 FROM exit_process e WHERE e.stage != 'COMPLETED'
AND e.core_id = NEW.core_id
AND e.person_id = NEW.person_id)
BEGIN SELECT RAISE(ABORT, 'cannot add live commitments during exit'); END""")
