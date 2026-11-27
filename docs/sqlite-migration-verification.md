# SQLDelight migration verification and runtime ordering

Updated: 2026-11-27

The repository now has a frozen, empty
[`persistence/src/main/sqldelight/databases/1.db`](../persistence/src/main/sqldelight/databases/README.md)
for SQLDelight's real schema comparison. Its SHA-256 is checked before verification;
a missing file fails instead of silently turning the verifier into `NO-SOURCE`.
Generation writes only to `persistence/build/schema-candidates/`, separately from
the committed input. `verifyMigrations` and `verifyDefinitions` are explicitly enabled.

SQLDelight uses the snapshot's filename to identify the source schema. A future
`1.sqm` describes the transition from schema 1 to 2; current `.sq` declarations must
describe the resulting schema. See the official
[2.0.2 migration documentation](https://sqldelight.github.io/sqldelight/2.0.2/jvm_sqlite/migrations/)
and [Gradle configuration](https://sqldelight.github.io/sqldelight/2.0.2/jvm_sqlite/gradle/).

The initial real comparison exposed SQLDelight 2.0.2's exponential traversal of
SchemaCrawler relationships: the worker consumed CPU in
`ObjectDifferDatabaseComparator` without finishing. Root Gradle buildscript resolution
now pins **only `app.cash.sqldelight:migrations:2.3.2`**, containing the upstream
[comparison-performance fix](https://github.com/sqldelight/sqldelight/pull/6073).
The SQLDelight plugin/compiler, dialect and application driver remain on 2.0.2.
This is a tested build-tool dependency override, not a wholesale SQLDelight upgrade;
review/remove it when upgrading the plugin. Schema/definition checks stay enabled,
and exact catalog tests continue independently of the comparator's normalization.

## What is verified now

```bash
./gradlew :persistence:check :test:migrationAcceptance :test:detekt --continue --console=plain
```

The normal `build`/CI path includes the same persistence check and historical tests.
The generated snapshot contains 38 empty application tables, two explicit indexes
and ten triggers. Its header retains `user_version = 0`; its filename is the
SQLDelight version identifier, not the runtime completion marker. No user data,
account, trust grant, signing key or verification receipt is included.

Two `SqlDelightBaselineTest` cases bring the focused suite to 45 tests. They hash
the snapshot, compare its exact catalog with the pinned declaration subset, assert
empty application tables and SQLite integrity/FKs, then initialize a disposable
copy containing one synthetic Person. Three openings preserve that row and produce
the full 91-object runtime catalog at version `1`. The committed snapshot is never
opened by tests in place or updated by ordinary builds.

Generated-schema verification does not cover the extra 41 guards, their behavioral
contracts, populated data migration or production opening order. The
[canonical inventory](sqlite-schema-inventory.md), four historical profiles and
opening-interruption tests remain required alongside the SQLDelight task.

## Checking the verifier itself

With JDK 21 and Gradle dependencies already cached by the check above:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 node scripts/schema-baseline/verify-migration-canary.mjs
```

The canary copies only build inputs and SQLDelight sources into a temporary project.
It does not copy credentials, production databases, Git metadata or the current
project's build outputs. It runs the actual verifier four times offline:

1. The unchanged baseline must pass.
2. Removing the temporary `identity.person_id` foreign key without a migration must
   fail and identify the foreign-key difference, guarding against weakened graph comparison.
3. Adding a nullable `person.migration_probe` column to the temporary `.sq` schema
   without a migration must fail and identify the column.
4. Adding the matching temporary `migrations/1.sqm` must pass.

It rejects `NO-SOURCE`/`SKIPPED` results, keeps logs in its printed temporary directory
and removes the temporary project even on failure. Original schema/snapshot bytes
are checked at exit. The canary is an explicit build-tool acceptance command, not
a production migration or a default runtime action. It requires review once the
first real migration changes the baseline-one assumptions.

## Required order before enabling a numbered production upgrade

The current application still accepts only legacy `0` and completed baseline `1`.
There is no committed `.sqm`, enabled version `2` or numbered production dispatcher
in this increment. The temporary canary does not change this contract.

The first real versioned change must implement and test this ordering explicitly:

1. Read the stored version and reject unsupported input before any migration writes.
2. Separate fresh creation from legacy adoption and a known versioned upgrade.
   Legacy `0` needs existing compatibility handling; it cannot be treated as an empty
   database or sent blindly through a migration intended for completed version `1`.
3. Apply the declared numbered steps in order, with a tested transaction/retry
   boundary. Preserve earlier snapshots and include direct/intermediate paths in
   the upgrade matrix. The SQLDelight verifier alone does not establish atomicity.
4. Install or update the runtime guards and validate the resulting full catalog,
   data invariants and current service behavior. `CREATE IF NOT EXISTS` alone cannot
   replace an existing changed trigger definition.
5. Record the target runtime version only after required schema/data/guard work
   succeeds, then expose the store to callers. Inject failures before and after
   schema, data, guard and marker boundaries and prove restart/retry behavior.

Keep ADR-030's existing completion-marker rule. SQLDelight's automatic driver schema
initializer is not enabled here; its version handling must not be added alongside
the store's marker handling without a unified transaction and ownership contract.
No business column or artificial production version bump is introduced just to
exercise migration tooling. Named release support and downgrade certification remain open.
