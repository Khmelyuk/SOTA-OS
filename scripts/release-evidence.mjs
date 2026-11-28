import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const modules = ['domain', 'application', 'protocol', 'persistence', 'security', 'sync', 'agent', 'api', 'test'];
const requiredReports = ['persistence', 'agent', 'api', 'test'];
const profiles = ['durable-p09', 'knowledge-versions', 'runtime-signed', 'runtime-p10'];
export const provenancePaths = [
  'gradle/wrapper/gradle-wrapper.properties',
  'persistence/src/main/sqldelight/databases/1.db',
  'test/src/test/resources/schema-baseline/v1.json',
  'test/src/test/resources/schema-baseline/historical-ddl-variants.json',
  ...profiles.flatMap(profile => ['schema.json', 'data.json'].map(name =>
    `test/src/test/resources/migrations/${profile}/${name}`)),
];
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const git = (root, ...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();

function regularPath(root, relative, directory = false) {
  let current = root;
  const parts = relative.split('/');
  for (const [index, part] of parts.entries()) {
    if (!part || part === '..' || part === '.') throw new Error(`Invalid evidence path: ${relative}`);
    current = path.join(current, part);
    const stat = fs.lstatSync(current);
    if (stat.isSymbolicLink() || (index < parts.length - 1 || directory ? !stat.isDirectory() : !stat.isFile())) {
      throw new Error(`Evidence requires regular files/directories: ${relative}`);
    }
  }
  return current;
}

// Deliberately accepts Gradle's testsuite report format, not arbitrary XML dialects.
export function reportCounts(xml) {
  const text = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '').trim();
  if (/<!DOCTYPE|<!ENTITY/.test(text)) throw new Error('Unsupported report declaration');
  const match = text.match(/^(?:<\?xml[^?]*\?>\s*)?<testsuite\b([^>]*)>[\s\S]*<\/testsuite>$/);
  if (!match) throw new Error('Expected a Gradle testsuite report');
  const counts = {};
  for (const key of ['tests', 'failures', 'errors', 'skipped']) {
    const values = [...match[1].matchAll(new RegExp(`\\b${key}="(\\d+)"`, 'g'))];
    if (values.length !== 1) throw new Error(`Missing or duplicate ${key} count`);
    counts[key] = Number(values[0][1]);
    if (!Number.isSafeInteger(counts[key])) throw new Error('Invalid report count');
  }
  if (!counts.tests || counts.failures || counts.errors || counts.skipped ||
      /<(?:failure|error|skipped)\b/.test(text) ||
      (text.match(/<testcase\b/g) ?? []).length !== counts.tests) {
    throw new Error('Evidence requires nonempty passing reports without skipped tests');
  }
  return counts;
}

function sourceIdentity(root) {
  if (git(root, 'status', '--porcelain', '--untracked-files=all')) throw new Error('Evidence requires a clean checkout');
  return { commit: git(root, 'rev-parse', 'HEAD'), tree: git(root, 'rev-parse', 'HEAD^{tree}') };
}

function ciIdentity(env, source) {
  if (env.GITHUB_ACTIONS !== 'true') return null;
  if (env.GITHUB_SHA !== source.commit) throw new Error('CI SHA does not match the checked-out commit');
  if (!/^[\w.-]+\/[\w.-]+$/.test(env.GITHUB_REPOSITORY ?? '') ||
      !/^\d+$/.test(env.GITHUB_RUN_ID ?? '') || !/^\d+$/.test(env.GITHUB_RUN_ATTEMPT ?? '')) {
    throw new Error('Incomplete GitHub run identity');
  }
  return { repository: env.GITHUB_REPOSITORY, runId: env.GITHUB_RUN_ID, attempt: env.GITHUB_RUN_ATTEMPT,
    url: `https://github.com/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}` };
}

export function collectEvidence(root, env = process.env) {
  root = fs.realpathSync(root);
  const source = sourceIdentity(root);
  const ci = ciIdentity(env, source);
  const files = [];
  const add = (relative, kind) => {
    const bytes = fs.readFileSync(regularPath(root, relative));
    if (!bytes.length) throw new Error(`Empty evidence file: ${relative}`);
    const entry = { path: relative, kind, bytes: bytes.length, sha256: sha256(bytes) };
    if (kind === 'junit') entry.counts = reportCounts(bytes.toString('utf8'));
    files.push({ entry, bytes });
  };
  const distributions = fs.readdirSync(regularPath(root, 'api/build/distributions', true)).sort();
  const archives = distributions.filter(name => /^api-[\w.-]+\.(zip|tar)$/.test(name));
  if (archives.length !== 2 || !archives.some(name => name.endsWith('.zip')) ||
      !archives.some(name => name.endsWith('.tar')) ||
      archives[0].slice(0, -4) !== archives[1].slice(0, -4)) {
    throw new Error('Expected one matching CLI ZIP/TAR distribution pair');
  }
  archives.forEach(name => add(`api/build/distributions/${name}`, 'distribution'));
  for (const module of modules) {
    const directory = `${module}/build/test-results/test`;
    const reports = fs.existsSync(path.join(root, directory))
      ? fs.readdirSync(regularPath(root, directory, true)).filter(name => /^TEST-[\w.$-]+\.xml$/.test(name)).sort() : [];
    if (requiredReports.includes(module) && !reports.length) throw new Error(`Missing ${module} test reports`);
    reports.forEach(name => add(`${directory}/${name}`, 'junit'));
  }
  const provenance = provenancePaths.map(relative => {
    const bytes = fs.readFileSync(regularPath(root, relative));
    return { path: relative, bytes: bytes.length, sha256: sha256(bytes) };
  });
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  for (const { entry } of files.filter(file => file.entry.kind === 'junit')) {
    for (const key of Object.keys(totals)) totals[key] += entry.counts[key];
  }
  const manifest = {
    formatVersion: 1, createdAt: new Date().toISOString(), source, ci,
    scope: 'Collected build outputs; require a successful matching CI run for build and smoke evidence. Not release or downgrade certification.',
    collectorNode: process.version, totals, files: files.map(file => file.entry), provenance,
  };
  if (JSON.stringify(sourceIdentity(root)) !== JSON.stringify(source)) throw new Error('Source changed during collection');
  const parent = path.join(root, 'build');
  if (!fs.existsSync(parent)) fs.mkdirSync(parent);
  regularPath(root, 'build', true);
  const output = path.join(parent, 'release-evidence');
  fs.mkdirSync(output); // Never overwrite an earlier bundle, including a symlink.
  try {
    for (const { entry, bytes } of files) {
      const destination = path.join(output, entry.path);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.writeFileSync(destination, bytes, { flag: 'wx' });
    }
    const json = JSON.stringify(manifest, null, 2) + '\n';
    fs.writeFileSync(path.join(output, 'manifest.json'), json, { flag: 'wx' });
    const sums = files.map(({ entry }) => `${entry.sha256}  ${entry.path}`);
    sums.push(`${sha256(json)}  manifest.json`);
    fs.writeFileSync(path.join(output, 'SHA256SUMS'), sums.join('\n') + '\n', { flag: 'wx' });
  } catch (error) {
    fs.rmSync(output, { recursive: true, force: true });
    throw error;
  }
  return { output, manifest };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 2) throw new Error('Usage: node scripts/release-evidence.mjs');
  const { output, manifest } = collectEvidence(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'));
  console.log(`Collected ${manifest.totals.tests} tests and ${manifest.files.length} files: ${output}`);
}
