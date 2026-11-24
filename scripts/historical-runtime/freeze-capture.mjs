// Authoring only: freeze a synthetic capture produced by CaptureRuntime.kt.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const [captureDirectory, name, kind = 'p09'] = process.argv.slice(2);
if (!captureDirectory || !/^[a-z0-9-]+$/.test(name ?? '') || !['p09', 'p10'].includes(kind)) {
  throw new Error('Usage: node scripts/historical-runtime/freeze-capture.mjs <capture-directory> <new-fixture-name> [p09|p10]');
}
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const schema = JSON.parse(readFileSync(resolve(captureDirectory, 'runtime-schema.json'), 'utf8'));
if (!/^[a-f0-9]{40}$/.test(schema.revision ?? '')) throw new Error('Full historical Git revision required');
for (const file of ['data.json', 'vectors.json']) {
  if (JSON.parse(readFileSync(resolve(captureDirectory, file), 'utf8')).fixtureOnly !== true) {
    throw new Error('Only synthetic fixture captures are supported');
  }
}
const git = args => execFileSync('git', args, { cwd: root });
const hash = value => createHash('sha256').update(value).digest('hex');
const helpers = kind === 'p10' ? ['test/src/test/kotlin/sotaos/test/acceptance/ExitFixture.kt'] : [
  'test/src/test/kotlin/sotaos/test/p09/ProductionFixtures.kt',
  'test/src/test/kotlin/sotaos/test/p09/HistoricalApprovalFixtures.kt'];
const paths = git(['ls-tree', '-r', '--name-only', schema.revision, 'persistence/src/main', ...helpers])
  .toString('utf8').trim().split('\n');
schema.sources = paths.map(path => ({ path, sha256: hash(git(['show', `${schema.revision}:${path}`])) }));
const author = kind === 'p10' ? 'scripts/historical-p10/CaptureP10.kt' : 'scripts/historical-runtime/CaptureRuntime.kt';
schema.authoring = { path: author, sha256: hash(readFileSync(resolve(root, author))) };
const target = resolve(root, 'test/src/test/resources/migrations', name);
mkdirSync(target); // Frozen profiles are never overwritten.
writeFileSync(resolve(target, 'schema.json'), JSON.stringify(schema, null, 2) + '\n');
for (const file of ['data.json', 'vectors.json']) copyFileSync(resolve(captureDirectory, file), resolve(target, file));
for (const file of ['schema.json', 'data.json', 'vectors.json']) {
  console.log(`${file}: ${hash(readFileSync(resolve(target, file)))}`);
}
