# Build identity and retained release evidence

Updated: 2026-11-28

CI now retains a `release-evidence-<full commit SHA>-<run attempt>` artifact after
its build, lint, tests, four CLI smoke checks and two-node rehearsal all succeed.
The artifact contains the CLI ZIP/TAR distributions, Gradle JUnit reports,
`manifest.json` and `SHA256SUMS`. The upload action is pinned by commit and retains
bundles for 30 days; an expiring CI artifact is not permanent release storage.

The manifest records the full checked-out commit and tree, GitHub run ID/attempt/link,
collection time, collector Node version, test totals, and the size/SHA-256 of every
copied distribution/report. It additionally records hashes of the wrapper properties,
frozen SQLDelight `1.db`, canonical catalogs and eight historical schema/data inputs.
Those fixture/database inputs are identified by hash, not copied into the bundle.
Only explicit distributions and ordinary `test` JUnit reports are copied; the
collector does not walk the workspace or include configuration, credentials, live
SQLite files, Gradle caches or unrelated files. The focused migration task's reports
are not counted again because its cases also run in the ordinary test suite.

## Produce and inspect a bundle

From a clean, committed checkout with completed build outputs:

```bash
node --test scripts/release-evidence.test.mjs
node scripts/release-evidence.mjs
cd build/release-evidence
sha256sum -c SHA256SUMS
```

Output is fixed at `build/release-evidence/`; an existing bundle is never overwritten.
Move a previous bundle to its archive location before collecting another. The
collector requires one nonempty matching CLI ZIP/TAR pair and nonempty passing
Gradle reports from persistence, agent, API and the integration-test module. Other
modules' ordinary test reports are included when present. Failures, errors, skipped
tests, inconsistent testcase counts, missing provenance files, symlinks and a dirty
checkout cause failure. In GitHub Actions, the checked-out SHA must equal `GITHUB_SHA`;
the run identity must be complete. PR runs identify the tested merge commit.

Eleven native Node tests exercise digest/copy equality, Git/CI identity, dirty source,
missing reports, missing/ambiguous/empty distributions, failed/skipped/inconsistent
reports, no partial output on report rejection, symlink rejection and overwrite refusal.
They need Node and Git, use only disposable temporary repositories, and run before
Gradle in CI. No added package installation is required.

## What this proves and what it does not

`SHA256SUMS` detects changed bundle contents. Obtain its trusted reference from the
matching GitHub workflow artifact and inspect that run's **final successful conclusion**;
the manifest cannot know the upload/job outcome while it is being written. CI places
collection after every existing acceptance step and does not use `always()` to turn
failed verification into release evidence. The upload fails if output is absent.

Local collection records the available outputs and sets `ci` to `null`. It does not
run Gradle or prove that reports/distributions are fresh or were built from the stated
commit. For release evidence, use the clean CI run; for a separate local rehearsal,
run the complete build and smoke commands first and retain their execution records.
JUnit parsing supports Gradle's single-testsuite format; this is not a general XML
validator or cryptographic attestation of test execution. Node collector tests and
CLI smoke steps are evidenced by the linked workflow, not the Gradle JUnit total.

The current runtime schema stays at `1`. Commit/tree and artifact digests distinguish
builds sharing the `0.1.0-mvp` filename, but neither an artifact name nor these hashes
certifies a named release upgrade, downgrade, all possible historical data or schema
version 2. A release still needs immutable release identity, declared supported source
profiles, migration-specific acceptance and a retained backup/recovery procedure.
See the [upgrade matrix](release-upgrade-matrix.md) and
[numbered migration contract](adr/ADR-031-numbered-sqlite-migrations.md).
