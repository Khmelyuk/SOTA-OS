package sotaos.persistence

import app.cash.sqldelight.db.SqlDriver

internal fun createRelationInventorySchemaIfMissing(driver: SqlDriver) {
    driver.execute(null, """CREATE TABLE IF NOT EXISTS relation_terms (
 relation_id TEXT NOT NULL PRIMARY KEY REFERENCES core_exit_relation(relation_id),
 terms_json TEXT NOT NULL, exit_policy TEXT NOT NULL)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS relation_obligation (
 relation_id TEXT NOT NULL REFERENCES relation_terms(relation_id),
 obligation_id TEXT NOT NULL UNIQUE REFERENCES exit_obligation(obligation_id),
 PRIMARY KEY(relation_id, obligation_id))""", 0)
    listOf("relation_terms", "relation_obligation").forEach { table ->
        listOf("UPDATE", "DELETE").forEach { operation ->
            driver.execute(null, """CREATE TRIGGER IF NOT EXISTS ${table}_no_${operation.lowercase()}
BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'relation terms are immutable'); END""", 0)
        }
    }
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS typed_relation_no_rewrite
BEFORE UPDATE ON core_exit_relation WHEN EXISTS (
SELECT 1 FROM relation_terms WHERE relation_id = OLD.relation_id)
AND (NEW.relation_id != OLD.relation_id OR NEW.person_id != OLD.person_id OR NEW.core_id != OLD.core_id
OR NEW.kind != OLD.kind OR NEW.description != OLD.description OR OLD.state = 'CLOSED')
BEGIN SELECT RAISE(ABORT, 'typed relation cannot be rewritten or reopened'); END""", 0)
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS typed_relation_no_delete
BEFORE DELETE ON core_exit_relation WHEN EXISTS (
SELECT 1 FROM relation_terms WHERE relation_id = OLD.relation_id)
BEGIN SELECT RAISE(ABORT, 'typed relation history is retained'); END""", 0)
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS linked_obligation_no_rewrite
BEFORE UPDATE ON exit_obligation WHEN EXISTS (
SELECT 1 FROM relation_obligation WHERE obligation_id = OLD.obligation_id)
AND (NEW.obligation_id != OLD.obligation_id OR NEW.person_id != OLD.person_id OR NEW.core_id != OLD.core_id
OR NEW.description != OLD.description OR (OLD.state = 'RETAINED' AND NEW.state = 'OPEN')
OR (OLD.state = 'FULFILLED' AND NEW.state != 'FULFILLED'))
BEGIN SELECT RAISE(ABORT, 'agreement obligations cannot be rewritten'); END""", 0)
    driver.execute(null, """CREATE TRIGGER IF NOT EXISTS linked_obligation_no_delete
BEFORE DELETE ON exit_obligation WHEN EXISTS (
SELECT 1 FROM relation_obligation WHERE obligation_id = OLD.obligation_id)
BEGIN SELECT RAISE(ABORT, 'agreement obligations are retained'); END""", 0)
}
