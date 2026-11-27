// Exercise the real SQLDelight verifier in a disposable, source-only project copy.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'sota-migration-canary-'));
const project = path.join(scratch, 'project');
const schemaPath = 'persistence/src/main/sqldelight/sotaos/persistence/Schema.sq';
const baselinePath = 'persistence/src/main/sqldelight/databases/1.db';
const originalSchema = fs.readFileSync(path.join(root, schemaPath));
const originalBaseline = fs.readFileSync(path.join(root, baselinePath));
const modules = ['domain', 'application', 'protocol', 'persistence', 'security', 'sync', 'agent', 'api', 'test'];
const copies = ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
  'gradle', 'config/detekt', 'persistence/src/main/sqldelight', ...modules.map(name => `${name}/build.gradle.kts`)];

function verify(label, expectedSuccess, expectedDifference = 'migration_probe') {
  const log = path.join(scratch, `${label}.log`);
  const fd = fs.openSync(log, 'w');
  let result;
  try {
    result = spawnSync(path.join(project, 'gradlew'), [':persistence:verifyMainSotaOsDatabaseMigration',
      '--offline', '--rerun-tasks', '--console=plain'], {
      cwd: project, env: process.env, stdio: ['ignore', fd, fd], timeout: 180_000,
    });
  } finally {
    fs.closeSync(fd);
  }
  const output = fs.readFileSync(log, 'utf8');
  if (result.error || (result.status === 0) !== expectedSuccess) {
    throw new Error(`Unexpected ${label} outcome; inspect ${log}: ${result.error ?? result.status}`);
  }
  if (!expectedSuccess && !output.includes(expectedDifference)) {
    throw new Error(`Failure did not identify ${expectedDifference}; inspect ${log}`);
  }
  if (expectedSuccess && !output.includes('BUILD SUCCESSFUL')) throw new Error(`Incomplete build: ${log}`);
  if (/verifyMainSotaOsDatabaseMigration (NO-SOURCE|SKIPPED)/.test(output)) {
    throw new Error(`Verifier did not run: ${log}`);
  }
  console.log(`${label}: ${expectedSuccess ? 'accepted' : 'rejected'} as expected`);
}

try {
  fs.mkdirSync(project);
  for (const relative of copies) {
    const destination = path.join(project, relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.cpSync(path.join(root, relative), destination, { recursive: true });
  }
  const migration = path.join(project, 'persistence/src/main/sqldelight/migrations/1.sqm');
  if (fs.existsSync(migration)) throw new Error('This baseline-one canary requires review after the first real migration.');
  verify('unchanged-baseline', true);
  const schema = originalSchema.toString('utf8');
  const identityReference = "external_refs_json TEXT NOT NULL DEFAULT '[]',\n    FOREIGN KEY (person_id) REFERENCES person(person_id)";
  if (!schema.includes(identityReference)) throw new Error('Identity foreign key changed; review the canary.');
  fs.writeFileSync(path.join(project, schemaPath), schema.replace(identityReference,
    "external_refs_json TEXT NOT NULL DEFAULT '[]'"));
  verify('missing-foreign-key', false, 'foreignKeys');
  const oldTable = 'CREATE TABLE person (\n    person_id   TEXT NOT NULL PRIMARY KEY,\n    created_at  TEXT NOT NULL\n);';
  const newTable = oldTable.replace('created_at  TEXT NOT NULL', 'created_at  TEXT NOT NULL,\n    migration_probe TEXT');
  if (!schema.includes(oldTable)) throw new Error('Person declaration changed; review the canary.');
  fs.writeFileSync(path.join(project, schemaPath), schema.replace(oldTable, newTable));
  verify('missing-migration', false);
  fs.mkdirSync(path.dirname(migration), { recursive: true });
  fs.writeFileSync(migration, 'ALTER TABLE person ADD COLUMN migration_probe TEXT;\n', { flag: 'wx' });
  verify('numbered-migration', true);
  console.log(`Migration verifier canary PASS; logs: ${scratch}`);
} finally {
  fs.rmSync(project, { recursive: true, force: true });
  if (!fs.readFileSync(path.join(root, schemaPath)).equals(originalSchema) ||
      !fs.readFileSync(path.join(root, baselinePath)).equals(originalBaseline)) {
    throw new Error('Source checkout changed during the canary; inspect concurrent work.');
  }
}
