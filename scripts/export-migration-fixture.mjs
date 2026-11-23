// Authoring tool only. Tests consume frozen resources and do not invoke Git or Node.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const [revision, name] = process.argv.slice(2);
if (!/^[a-f0-9]{40}$/.test(revision ?? '') || !/^[a-z0-9-]+$/.test(name ?? '')) {
  throw new Error('Usage: node scripts/export-migration-fixture.mjs <full-commit-sha> <new-fixture-name>');
}
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8' });
const prefix = 'persistence/src/main/sqldelight/';
const files = git(['ls-tree', '-r', '--name-only', revision, prefix]).trim().split('\n')
  .filter(path => path.endsWith('.sq')).sort();
if (files.length === 0) throw new Error('No historical SQLDelight schema files found');
const sources = [];
const statements = [];
for (const path of files) {
  const source = git(['show', `${revision}:${path}`]);
  sources.push({ path, sha256: createHash('sha256').update(source).digest('hex') });
  let pending = [];
  let trigger = false;
  for (const line of source.split('\n')) {
    if (pending.length === 0) {
      if (!/^CREATE\b/i.test(line)) continue;
      if (!/^CREATE (?:UNIQUE )?(?:TABLE|INDEX|TRIGGER)\b/i.test(line)) {
        throw new Error(`Unsupported DDL in ${path}: ${line}`);
      }
      trigger = /^CREATE TRIGGER\b/i.test(line);
    }
    pending.push(line);
    if ((trigger && /\bEND;\s*$/i.test(line)) || (!trigger && /;\s*$/.test(line))) {
      statements.push(pending.join('\n'));
      pending = [];
    }
  }
  if (pending.length) throw new Error(`Unterminated DDL in ${path}`);
}
// Resolve cross-file dependencies without modifying the captured SQL text.
const rank = statement => /^CREATE TABLE/i.test(statement) ? 0 : /^CREATE (?:UNIQUE )?INDEX/i.test(statement) ? 1 : 2;
statements.sort((left, right) => rank(left) - rank(right));
const target = resolve(root, 'test/src/test/resources/migrations', name);
mkdirSync(dirname(target), { recursive: true });
mkdirSync(target); // Refuse to overwrite an existing frozen fixture.
writeFileSync(resolve(target, 'schema.json'), JSON.stringify({ revision, sources, statements }, null, 2) + '\n');
console.log(`${name}: ${sources.length} source files, ${statements.length} DDL statements`);
