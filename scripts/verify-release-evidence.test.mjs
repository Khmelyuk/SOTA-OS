import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { verifyEvidence, parseVerificationArgs } from './verify-release-evidence.mjs';

const digest = value => crypto.createHash('sha256').update(value).digest('hex');
const commit = 'a'.repeat(40);
const counts = { tests: 1, failures: 0, errors: 0, skipped: 0 };
const report = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="ok"/></testsuite>';
const ci = { repository: 'Khmelyuk/SOTA-OS', runId: '123', attempt: '2',
  url: 'https://github.com/Khmelyuk/SOTA-OS/actions/runs/123' };
function save(root, manifest) {
  const json = JSON.stringify(manifest, null, 2) + '\n';
  fs.writeFileSync(path.join(root, 'manifest.json'), json);
  fs.writeFileSync(path.join(root, 'SHA256SUMS'),
    [...manifest.files.map(e => `${e.sha256}  ${e.path}`), `${digest(json)}  manifest.json`].join('\n') + '\n');
}
function fixture(block) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sota-verify-evidence-'));
  const files = [];
  const add = (relative, bytes, kind) => {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.writeFileSync(path.join(root, relative), bytes);
    files.push({ path: relative, kind, bytes: Buffer.byteLength(bytes), sha256: digest(bytes),
      ...(kind === 'junit' ? { counts: { ...counts } } : {}) });
  };
  for (const ext of ['zip', 'tar']) add(`api/build/distributions/api-test.${ext}`, 'fixture-' + ext, 'distribution');
  for (const module of ['api', 'agent', 'persistence', 'test'])
    add(`${module}/build/test-results/test/TEST-example.xml`, report, 'junit');
  const manifest = { formatVersion: 1, source: { commit, tree: 'b'.repeat(40) }, ci: { ...ci },
    files, totals: { ...counts, tests: 4 }, provenance: [{ path: 'reference.txt', bytes: 1, sha256: digest('x') }] };
  save(root, manifest);
  try { block(root, manifest); } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
const verify = root => verifyEvidence(root, { commit, ci });

test('matching detached bundle verifies without a checkout, mutation or referenced source files', () => fixture((root, m) => {
  const large = Buffer.alloc(150000, 42);
  fs.writeFileSync(path.join(root, m.files[0].path), large);
  Object.assign(m.files[0], { bytes: large.length, sha256: digest(large) });
  save(root, m);
  const before = fs.readFileSync(path.join(root, 'manifest.json'));
  assert.deepEqual(verify(root), { source: m.source, ci, totals: m.totals, files: 6 });
  assert.deepEqual(fs.readFileSync(path.join(root, 'manifest.json')), before);
  assert.equal(fs.existsSync(path.join(root, 'reference.txt')), false);
  const output = execFileSync(process.execPath, [path.resolve('scripts/verify-release-evidence.mjs'), root,
    '--commit', commit, '--repository', ci.repository, '--run', ci.runId, '--attempt', ci.attempt],
  { cwd: os.tmpdir(), encoding: 'utf8' });
  assert.match(output, /4 tests; 6 files/);
  assert.match(output, /final successful CI conclusion separately/);
}));

test('wrong commit, repository, run or attempt fails; local evidence cannot satisfy a CI expectation', () => fixture((root, m) => {
  assert.throws(() => verifyEvidence(root, { commit: 'c'.repeat(40) }), /Source identity/);
  for (const key of ['repository', 'runId', 'attempt'])
    assert.throws(() => verifyEvidence(root, { commit, ci: { ...ci, [key]: 'other' } }), /CI identity/);
  m.ci = null; save(root, m);
  assert.equal(verifyEvidence(root, { commit }).ci, null);
  assert.throws(() => verify(root), /CI identity/);
}));

test('changed or truncated distribution bytes are rejected', () => fixture((root, m) => {
  const file = path.join(root, m.files[0].path);
  for (const data of ['changed-zip', 'short']) {
    fs.writeFileSync(file, data);
    assert.throws(() => verify(root), /File (digest|size) mismatch/);
    assert.equal(fs.readFileSync(file, 'utf8'), data);
  }
}));

test('missing and additional files fail the exact inventory', () => fixture((root, m) => {
  fs.writeFileSync(path.join(root, 'unexpected.txt'), 'extra');
  assert.throws(() => verify(root), /inventory mismatch/);
  fs.unlinkSync(path.join(root, 'unexpected.txt'));
  fs.unlinkSync(path.join(root, m.files[0].path));
  assert.throws(() => verify(root), /inventory mismatch/);
}));

test('duplicate, absolute and traversal manifest paths are rejected', () => {
  for (const bad of ['../outside', '/tmp/outside', 'api//build/file', 'api/./build/file', 'api\\outside'])
    fixture((root, m) => { m.files[0].path = bad; save(root, m); assert.throws(() => verify(root), /Unsafe/); });
  fixture((root, m) => { m.files.push(m.files[0]); save(root, m); assert.throws(() => verify(root), /duplicate/); });
});

test('file, directory and root symlinks are rejected', () => fixture((root, m) => {
  const file = path.join(root, m.files[0].path);
  fs.unlinkSync(file); fs.symlinkSync('/nonexistent-fixture-target', file);
  assert.throws(() => verify(root), /symlinks/);
  fs.unlinkSync(file); fs.writeFileSync(file, 'fixture-zip');
  fs.symlinkSync(path.join(root, 'api'), path.join(root, 'linked-directory'));
  assert.throws(() => verify(root), /symlinks/);
  fs.unlinkSync(path.join(root, 'linked-directory'));
  const link = root + '-link';
  try { fs.symlinkSync(root, link); assert.throws(() => verify(link), /regular directories/); }
  finally { fs.unlinkSync(link); }
}));

test('unsupported format and malformed identity or size metadata fail', () => {
  for (const change of [m => m.formatVersion = 2, m => m.source.tree = 'short',
    m => m.ci.url = 'https://example.invalid', m => m.files[0].bytes = -1,
    m => m.files[0].sha256 = 'bad', m => m.files[0].kind = 'unknown'])
    fixture((root, m) => { change(m); save(root, m); assert.throws(() => verify(root)); });
});

test('checksum index cannot omit, duplicate, modify or append entries', () => fixture(root => {
  const file = path.join(root, 'SHA256SUMS'), original = fs.readFileSync(file, 'utf8');
  for (const value of [original.split('\n').slice(1).join('\n'), original + original,
    original.replace(/^[a-f0-9]/, 'z'), original + 'unlisted\n']) {
    fs.writeFileSync(file, value);
    assert.throws(() => verify(root), /Checksum index/);
  }
}));

test('rehashed failing reports, mismatched report counts and totals still fail', () => {
  fixture((root, m) => {
    const entry = m.files.find(e => e.kind === 'junit');
    const failed = report.replace('failures="0"', 'failures="1"');
    fs.writeFileSync(path.join(root, entry.path), failed); entry.sha256 = digest(failed); save(root, m);
    assert.throws(() => verify(root), /passing reports/);
  });
  fixture((root, m) => { m.files[2].counts.tests = 2; save(root, m); assert.throws(() => verify(root), /Report counts/); });
  fixture((root, m) => { m.totals.tests = 5; save(root, m); assert.throws(() => verify(root), /Test totals/); });
});

test('missing required report modules and unmatched distribution pair fail', () => {
  fixture((root, m) => { m.files.pop(); save(root, m); assert.throws(() => verify(root), /required module/); });
  fixture((root, m) => { m.files[0].path = 'api/build/distributions/api-other.zip'; save(root, m);
    assert.throws(() => verify(root), /distribution pair/); });
});

test('malformed or duplicate provenance is rejected without reading referenced paths', () => {
  for (const change of [m => m.provenance = [], m => m.provenance[0].path = '../outside',
    m => m.provenance.push(m.provenance[0]), m => m.provenance[0].sha256 = 'bad'])
    fixture((root, m) => { change(m); save(root, m); assert.throws(() => verify(root), /provenance/); });
});

test('CLI requires full commit and rejects incomplete CI expectations and unknown or repeated options', () => {
  assert.deepEqual(parseVerificationArgs(['bundle', '--commit', commit]), { root: 'bundle', expected: { commit } });
  for (const args of [[], ['bundle'], ['bundle', '--commit', 'short'],
    ['bundle', '--commit', commit, '--run', '1'], ['bundle', '--commit', commit, '--unknown', '1'],
    ['bundle', '--commit', commit, '--commit', commit], ['bundle', '--commit', commit, '--attempt']])
    assert.throws(() => parseVerificationArgs(args));
});
