import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { collectEvidence, provenancePaths, reportCounts } from './release-evidence.mjs';

const report = '<?xml version="1.0"?><testsuite name="fixture" tests="1" failures="0" errors="0" skipped="0">' +
  '<testcase name="passes"/><system-out><![CDATA[<failure>not XML</failure>]]></system-out></testsuite>';
const put = (root, relative, data) => {
  const destination = path.join(root, relative);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, data);
};
const git = (root, ...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
function fixture(block) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sota-evidence-test-'));
  try {
    put(root, '.gitignore', '**/build/\n');
    for (const relative of provenancePaths) put(root, relative, 'frozen fixture\n');
    for (const extension of ['zip', 'tar']) put(root, `api/build/distributions/api-test.${extension}`, `artifact ${extension}`);
    for (const module of ['persistence', 'agent', 'api', 'test']) put(root, `${module}/build/test-results/test/TEST-fixture.xml`, report);
    git(root, 'init', '-q');
    git(root, 'add', '.');
    git(root, '-c', 'user.name=Evidence test', '-c', 'user.email=evidence@example.invalid',
      '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Fixture');
    block(root);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}

test('bundle binds exact commit, artifacts, reports and provenance without copying database inputs', () => fixture(root => {
  const { output, manifest } = collectEvidence(root, {});
  assert.equal(manifest.source.commit, git(root, 'rev-parse', 'HEAD'));
  assert.equal(manifest.source.tree, git(root, 'rev-parse', 'HEAD^{tree}'));
  assert.equal(manifest.ci, null);
  assert.deepEqual(manifest.totals, { tests: 4, failures: 0, errors: 0, skipped: 0 });
  assert.equal(manifest.provenance.length, provenancePaths.length);
  assert.equal(manifest.files.length, 6);
  assert.equal(fs.existsSync(path.join(output, provenancePaths[1])), false);
  const sums = fs.readFileSync(path.join(output, 'SHA256SUMS'), 'utf8').trim().split('\n');
  assert.equal(sums.length, 7);
  for (const line of sums) {
    const [expected, relative] = line.split('  ');
    const actual = crypto.createHash('sha256').update(fs.readFileSync(path.join(output, relative))).digest('hex');
    assert.equal(actual, expected);
  }
}));

test('matching GitHub identity is recorded and mismatched or incomplete identity is rejected', () => fixture(root => {
  const env = { GITHUB_ACTIONS: 'true', GITHUB_SHA: git(root, 'rev-parse', 'HEAD'),
    GITHUB_REPOSITORY: 'example/project', GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2' };
  assert.throws(() => collectEvidence(root, { ...env, GITHUB_SHA: '0'.repeat(40) }), /CI SHA/);
  assert.throws(() => collectEvidence(root, { ...env, GITHUB_RUN_ID: '' }), /Incomplete/);
  assert.equal(collectEvidence(root, env).manifest.ci.url, 'https://github.com/example/project/actions/runs/123');
}));

test('tracked changes and untracked source files are rejected', () => fixture(root => {
  put(root, 'unexpected.txt', 'untracked');
  assert.throws(() => collectEvidence(root, {}), /clean checkout/);
  fs.unlinkSync(path.join(root, 'unexpected.txt'));
  put(root, provenancePaths[0], 'changed');
  assert.throws(() => collectEvidence(root, {}), /clean checkout/);
}));

test('missing required reports are rejected', () => fixture(root => {
  fs.rmSync(path.join(root, 'persistence/build/test-results'), { recursive: true });
  assert.throws(() => collectEvidence(root, {}), /Missing persistence/);
}));

test('missing or ambiguous distribution pairs are rejected', () => fixture(root => {
  put(root, 'api/build/distributions/api-old.zip', 'old');
  assert.throws(() => collectEvidence(root, {}), /distribution pair/);
  fs.unlinkSync(path.join(root, 'api/build/distributions/api-old.zip'));
  fs.unlinkSync(path.join(root, 'api/build/distributions/api-test.zip'));
  assert.throws(() => collectEvidence(root, {}), /distribution pair/);
}));

test('failed skipped empty and inconsistent reports are rejected', () => {
  for (const invalid of [report.replace('failures="0"', 'failures="1"'),
    report.replace('errors="0"', 'errors="1"'), report.replace('skipped="0"', 'skipped="1"'),
    report.replace('tests="1"', 'tests="0"'), report.replace('tests="1"', 'tests="2"'),
    report.replace('<testcase name="passes"/>', '<testcase><failure message="failure"/></testcase>'),
    report.replace('tests="1"', 'tests="1" tests="1"'), '<testsuites/>', '<!DOCTYPE x>' + report]) {
    assert.throws(() => reportCounts(invalid));
  }
});

test('report rejection leaves no partial output', () => fixture(root => {
  put(root, 'test/build/test-results/test/TEST-fixture.xml', report.replace('failures="0"', 'failures="1"'));
  assert.throws(() => collectEvidence(root, {}));
  assert.equal(fs.existsSync(path.join(root, 'build/release-evidence')), false);
}));

test('symlinked artifacts and report directories cannot include external files', () => fixture(root => {
  const artifact = path.join(root, 'api/build/distributions/api-test.zip');
  fs.unlinkSync(artifact);
  fs.symlinkSync(path.join(root, '.gitignore'), artifact);
  assert.throws(() => collectEvidence(root, {}), /regular files/);
  fs.unlinkSync(artifact);
  fs.writeFileSync(artifact, 'artifact');
  const directory = path.join(root, 'test/build/test-results/test');
  fs.rmSync(directory, { recursive: true });
  fs.symlinkSync(path.join(root, 'api/build/test-results/test'), directory);
  assert.throws(() => collectEvidence(root, {}), /regular files/);
}));

test('existing evidence is never overwritten', () => fixture(root => {
  const { output } = collectEvidence(root, {});
  const before = fs.readFileSync(path.join(output, 'manifest.json'));
  assert.throws(() => collectEvidence(root, {}), /EEXIST/);
  assert.deepEqual(fs.readFileSync(path.join(output, 'manifest.json')), before);
}));

test('symlinked output parent is rejected', () => fixture(root => {
  fs.symlinkSync(path.join(root, 'api/build'), path.join(root, 'build'));
  git(root, 'add', 'build');
  git(root, '-c', 'user.name=Evidence test', '-c', 'user.email=evidence@example.invalid',
    '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Tracked symlink');
  assert.throws(() => collectEvidence(root, {}), /regular files/);
  assert.equal(fs.existsSync(path.join(root, 'api/build/release-evidence')), false);
}));

test('empty distributions are rejected', () => fixture(root => {
  put(root, 'api/build/distributions/api-test.zip', '');
  assert.throws(() => collectEvidence(root, {}), /Empty evidence/);
}));
