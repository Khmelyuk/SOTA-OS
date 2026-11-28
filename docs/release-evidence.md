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
The Genesis proposal, ADR-032 review package/source manifest and stopped-node recovery
runbook are also identified in provenance by hash. Their status remains Proposed;
hashing review inputs neither accepts the ADR nor proves its planned tests pass.
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

## Verify a retained or downloaded bundle

Use the verifier from a trusted checkout, and obtain the full expected commit from
the selected workflow run. For a GitHub bundle, also pin repository, run and attempt:

```bash
node scripts/verify-release-evidence.mjs /path/to/extracted/release-evidence \
  --commit FULL_COMMIT_SHA --repository Khmelyuk/SOTA-OS --run RUN_ID --attempt ATTEMPT
```

For a local bundle, omit all three CI options; `--commit` is always required. The
verifier runs without Git, network access, a build or a source checkout alongside
the bundle. Keep the two repository scripts together because it imports the existing
Gradle report parser. It reads files without modifying them and does not extract ZIP/TAR
contents, install the distribution or run bundled code.

Verification checks format version 1, the requested commit and optional exact CI
identity, the complete file inventory, one matching CLI ZIP/TAR pair, required module
reports, byte lengths and SHA-256, report counts and recomputed aggregate totals.
`SHA256SUMS` must match every manifest entry plus the manifest itself, with no missing,
duplicate or extra lines. Reports must pass the same nonempty/no-failure/no-skip parser
as collection. Absolute/traversal paths, duplicate file entries, symlinks and nonregular
files are rejected. Distribution hashes are streamed; manifests/checksum indexes are
limited to 4 MiB and individual XML reports to 32 MiB. Unsupported formats fail closed.

Provenance references are checked for valid, unique path/hash/size metadata; their
source bytes are not included in the bundle and are **not reverified**. The verifier
does not claim that the listed provenance set is sufficient for a release. Likewise,
internal consistency and an expected commit do not authenticate a self-contained
manifest: an attacker who replaces files and all hashes can construct a consistent
bundle. Obtain it through a trusted artifact channel and separately confirm the run's
final successful conclusion. Local `ci: null` evidence cannot satisfy CI expectations.
Use a trusted parent directory and stable extracted files; concurrent host-level path
replacement is outside this check. Access timestamps may change under filesystem policy.

Twelve verifier tests cover detached CLI use, commit/run identity and local/CI separation,
file damage/inventory, unsafe and duplicate paths, file/directory/root symlinks,
malformed metadata, checksum-index changes, rehashed failed reports, count mismatches,
required modules/distribution pairs, provenance and strict arguments. Run all 23 tests:

```bash
node --test scripts/release-evidence.test.mjs scripts/verify-release-evidence.test.mjs
```

CI now verifies its freshly collected bundle against its own commit/repository/run/attempt
before upload. A failed verification prevents artifact publication. This is an additional
consistency gate, not a signature, named release approval or new schema compatibility claim.

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
