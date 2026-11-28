import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { reportCounts } from './release-evidence.mjs';

const modules = ['domain', 'application', 'protocol', 'persistence', 'security', 'sync', 'agent', 'api', 'test'];
const requiredReports = ['persistence', 'agent', 'api', 'test'];
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const check = (condition, message) => { if (!condition) throw new Error(message); };
const sha = (value, length) => typeof value === 'string' && new RegExp(`^[a-f0-9]{${length}}$`).test(value);
const safePath = value => typeof value === 'string' && /^[\w.$/-]+$/.test(value) &&
  value.split('/').every(part => part && part !== '.' && part !== '..');
const countsEqual = (left, right) => ['tests', 'failures', 'errors', 'skipped'].every(key => left?.[key] === right[key]);

function inventory(root, relative = '') {
  const current = path.join(root, relative);
  const stat = fs.lstatSync(current);
  check(stat.isDirectory() && !stat.isSymbolicLink(), 'Bundle requires regular directories');
  return fs.readdirSync(current).flatMap(name => {
    const child = relative ? `${relative}/${name}` : name;
    check(safePath(child), 'Unsafe bundle path');
    const info = fs.lstatSync(path.join(root, child));
    check(!info.isSymbolicLink(), 'Bundle symlinks are forbidden');
    if (info.isDirectory()) return inventory(root, child);
    check(info.isFile(), 'Bundle requires regular files');
    return [child];
  });
}

function readText(root, relative, limit) {
  const file = path.join(root, relative);
  check(fs.lstatSync(file).size <= limit, `Oversized metadata: ${relative}`);
  const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    check(fs.fstatSync(fd).isFile(), 'Bundle requires regular files');
    return fs.readFileSync(fd);
  } finally { fs.closeSync(fd); }
}

function verifyFile(root, entry) {
  check(Number.isSafeInteger(entry.bytes) && entry.bytes > 0 && sha(entry.sha256, 64), 'Invalid file metadata');
  const fd = fs.openSync(path.join(root, entry.path), fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const stat = fs.fstatSync(fd);
    check(stat.isFile() && stat.size === entry.bytes, `File size mismatch: ${entry.path}`);
    const digest = crypto.createHash('sha256');
    const buffer = Buffer.alloc(64 * 1024);
    let total = 0;
    let n;
    while ((n = fs.readSync(fd, buffer, 0, Math.min(buffer.length, entry.bytes - total + 1), null)) !== 0) {
      total += n;
      check(total <= entry.bytes, `File grew during verification: ${entry.path}`);
      digest.update(buffer.subarray(0, n));
    }
    check(total === entry.bytes && digest.digest('hex') === entry.sha256, `File digest mismatch: ${entry.path}`);
  } finally { fs.closeSync(fd); }
}

function checkIdentity(manifest, expected) {
  check(sha(expected.commit, 40), 'Expected commit must be a full lowercase SHA');
  check(manifest.formatVersion === 1, 'Unsupported evidence format');
  check(manifest.source?.commit === expected.commit && sha(manifest.source?.tree, 40), 'Source identity mismatch');
  if (manifest.ci !== null) {
    const ci = manifest.ci;
    check(ci && /^[\w.-]+\/[\w.-]+$/.test(ci.repository) && /^[1-9]\d*$/.test(ci.runId) &&
      /^[1-9]\d*$/.test(ci.attempt) && typeof ci.runId === 'string' && typeof ci.attempt === 'string' &&
      ci.url === `https://github.com/${ci.repository}/actions/runs/${ci.runId}`, 'Invalid CI identity');
  }
  if (expected.ci) {
    check(manifest.ci !== null && ['repository', 'runId', 'attempt'].every(key =>
      typeof expected.ci[key] === 'string' && manifest.ci[key] === expected.ci[key]), 'CI identity mismatch');
  }
}

/** Internal consistency only. Obtain the bundle and expected identity through a trusted channel. */
export function verifyEvidence(root, expected) {
  root = path.resolve(root);
  const actual = inventory(root);
  check(actual.includes('manifest.json') && actual.includes('SHA256SUMS'), 'Missing manifest or checksums');
  const manifestBytes = readText(root, 'manifest.json', 4 * 1024 * 1024);
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  checkIdentity(manifest, expected);
  check(Array.isArray(manifest.files) && manifest.files.length > 0, 'Missing file entries');
  const paths = new Set();
  const distributions = [];
  const reports = new Set();
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  for (const entry of manifest.files) {
    check(safePath(entry.path) && !paths.has(entry.path), 'Unsafe or duplicate manifest path');
    paths.add(entry.path);
    if (entry.kind === 'distribution') {
      check(/^api\/build\/distributions\/api-[\w.-]+\.(zip|tar)$/.test(entry.path), 'Invalid distribution path');
      distributions.push(entry.path);
    } else {
      const match = entry.path.match(/^([^/]+)\/build\/test-results\/test\/TEST-[\w.$-]+\.xml$/);
      check(entry.kind === 'junit' && match && modules.includes(match[1]), 'Invalid report path or kind');
      reports.add(match[1]);
    }
  }
  check(distributions.length === 2 && distributions.some(p => p.endsWith('.zip')) &&
    distributions.some(p => p.endsWith('.tar')) && distributions[0].slice(0, -4) === distributions[1].slice(0, -4),
  'Expected one matching distribution pair');
  check(requiredReports.every(module => reports.has(module)), 'Missing required module reports');
  const expectedPaths = [...paths, 'manifest.json', 'SHA256SUMS'].sort();
  check(JSON.stringify(actual.sort()) === JSON.stringify(expectedPaths), 'Bundle file inventory mismatch');
  for (const entry of manifest.files) {
    verifyFile(root, entry);
    if (entry.kind === 'junit') {
      const counts = reportCounts(readText(root, entry.path, 32 * 1024 * 1024).toString('utf8'));
      check(countsEqual(entry.counts, counts), 'Report counts mismatch');
      for (const key of Object.keys(totals)) totals[key] += counts[key];
    }
  }
  check(Object.values(totals).every(Number.isSafeInteger) && countsEqual(manifest.totals, totals), 'Test totals mismatch');
  check(Array.isArray(manifest.provenance) && manifest.provenance.length > 0, 'Missing provenance references');
  const references = new Set();
  for (const entry of manifest.provenance) {
    check(safePath(entry.path) && !references.has(entry.path) && Number.isSafeInteger(entry.bytes) &&
      entry.bytes > 0 && sha(entry.sha256, 64), 'Invalid provenance reference');
    references.add(entry.path);
  }
  const sums = manifest.files.map(entry => `${entry.sha256}  ${entry.path}`);
  sums.push(`${hash(manifestBytes)}  manifest.json`);
  check(readText(root, 'SHA256SUMS', 4 * 1024 * 1024).toString('utf8') === sums.join('\n') + '\n',
    'Checksum index mismatch');
  return { source: manifest.source, ci: manifest.ci, totals, files: paths.size };
}

export function parseVerificationArgs(args) {
  check(args.length >= 3 && !args[0].startsWith('--'), 'Usage: BUNDLE --commit SHA [--repository OWNER/REPO --run ID --attempt N]');
  const options = {};
  for (let index = 1; index < args.length; index += 2) {
    const name = args[index];
    check(['--commit', '--repository', '--run', '--attempt'].includes(name) &&
      !Object.hasOwn(options, name) && args[index + 1] && !args[index + 1].startsWith('--'), 'Invalid or duplicate option');
    options[name] = args[index + 1];
  }
  check(sha(options['--commit'], 40), 'Expected commit must be a full lowercase SHA');
  const ciParts = ['--repository', '--run', '--attempt'].filter(key => Object.hasOwn(options, key));
  check(ciParts.length === 0 || ciParts.length === 3, 'Supply repository, run and attempt together');
  return { root: args[0], expected: { commit: options['--commit'], ...(ciParts.length ? { ci: {
    repository: options['--repository'], runId: options['--run'], attempt: options['--attempt'],
  } } : {}) } };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { root, expected } = parseVerificationArgs(process.argv.slice(2));
  const result = verifyEvidence(root, expected);
  console.log(`Verified bundle consistency: ${result.source.commit}; ${result.totals.tests} tests; ${result.files} files.`);
  console.log('Verify the trusted artifact origin and final successful CI conclusion separately.');
}
