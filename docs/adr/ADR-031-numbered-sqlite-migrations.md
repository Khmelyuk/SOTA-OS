# ADR-031 — Transactional numbered SQLite migrations

Status: ACCEPTED
Date: 2026-11-27
Level: Implementation Architecture

## Decision

`SqlDelightStore` delegates opening to `initializeStoreSchema`. The internal
`StoreMigrationPlan` binds the generated SQLDelight `Schema` to explicitly registered
runtime/data steps and the current runtime installer/validator. Every source version
from `1` to `Schema.version - 1` must have a registration, including intentional no-ops.
An added `.sqm` without corresponding runtime review fails before opening. This is
build-owned configuration, not a CLI option or executable content read from a database.
Production remains at schema `1`: this increment adds no business column, `.sqm` or
artificial version bump. Versions `2` and above remain unsupported by this build.

The store rejects an enclosing SQLDelight transaction. Version inspection and core-table
preflight precede any schema/data writes. There are three separate paths:

- **Fresh empty version 0:** generated creation, current runtime installation,
  validation and target marker share one transaction. No historical data backfill runs.
- **Populated legacy version 0:** preserve ADR-030's idempotent, non-atomic compatibility
  adoption and mark completed baseline `1`. Then run any numbered upgrade. If that
  upgrade fails, the completed legacy adoption remains at `1` and is not replayed as
  a numbered migration from `0`.
- **Known completed older version:** in one transaction, recheck the source marker,
  call `Schema.migrate(driver, source, source + 1)` for each pending step, and execute
  its registered runtime/data callback. Install the current runtime guards, recheck
  core tables, execute target-specific validation and require an empty
  `PRAGMA foreign_key_check`. Write the target marker last and commit before returning
  the store. All pending numbered steps commit together, not one marker per step.

Opening the current version reruns its idempotent runtime installer without replaying
numbered data callbacks or rewriting the marker. The installer must produce current
guards on fresh creation as well as upgrades; changing a trigger requires an explicit
replacement, not `CREATE IF NOT EXISTS` alone. Future registrations must retain legacy
baseline compatibility separately from the target installer. Callbacks may perform
transactional SQLite work only; they must not commit, change the version themselves,
use a separate connection, perform network/file side effects or disable constraints.

SQLDelight 2.0.2 leaves an active JDBC transaction when its commit throws; the new
reader-contention regression reproduced a retained database lock. The transaction
wrapper explicitly rolls back any stranded transaction and closes its JDBC connection.
Cleanup failures are attached to the original error instead of replacing it. The
[upstream JDBC implementation](https://raw.githubusercontent.com/sqldelight/sqldelight/2.0.2/drivers/jdbc-driver/src/main/kotlin/app/cash/sqldelight/driver/jdbc/JdbcDriver.kt)
and [SQLite connection manager](https://raw.githubusercontent.com/sqldelight/sqldelight/2.0.2/drivers/sqlite-driver/src/main/kotlin/app/cash/sqldelight/driver/jdbc/sqlite/JdbcSqliteDriver.kt)
explain why ordinary driver close is insufficient for that file-backed failure path.

The caller owns the driver and must close it after any opening failure, including
`COMMIT` errors, then retry with a new driver. No usable store is returned on failure.
There is no automatic loop, silent repair or fallback to an older schema.

## Evidence

`persistence/src/test/kotlin/sotaos/persistence/` contains 33 tests:

- 17 `NumberedMigrationTest` cases use a test-only `SqlSchema` implementing concrete
  SQLite DDL for versions 2/3, data backfills, a changed guard and the actual store
  opening path. Direct `1 → 3` and staged `1 → 2 → 3` agree; three reopens do not
  replay data. Seven injected statement boundaries, intermediate-source rollback,
  legacy adoption, fresh rollback/backfill exclusion, invalid registrations, unknown
  versions, nested opening, target/FK validation and a real reader-blocked commit
  cover rejection and retry. These schemas are test doubles, not released `.sqm` files.
- 16 `MigrationCrashRecoveryTest` cases launch a separate JVM with the same driver
  and store. `Runtime.halt(73)` bypasses driver close at seven boundaries in both
  DELETE and WAL modes: after first DDL, first backfill, second step, guard removal,
  guard installation, before the marker and after the marker but before commit.
  Reopening recovers the original catalog, rows and version; retry completes once.
  Halting after commit but before close retains the completed version without replay.
- The existing 45 historical/canonical/verifier tests and CLI opening tests retain
  their original contracts. The exact 91-object production catalog, frozen `1.db`
  and historical fixtures remain unchanged. SQLDelight's real generated migration
  verification remains covered by the isolated verifier canary.

## Limits and first release migration

Process termination is tested at these boundaries, not at every SQLite instruction.
This is not hardware power-loss, disk-full/corruption testing or cross-process migration
coordination. SQLite contention may fail opening; close and retry explicitly. Raw SQL
transactions outside SQLDelight remain a trusted low-level boundary. Validation is
bounded and is not a complete fingerprint of an arbitrary input database.

Before enabling a real version 2, provide a domain-motivated `.sqm`, matching current
`.sq` declarations, explicit runtime/data registration, a fresh-compatible current
installer and target assertions. Pin populated old/current profiles and verify direct,
intermediate, crash/retry and fresh-creation equivalence for that actual change.
Preserve baseline `1.db`; keep generated verification enabled. This dispatcher is not
named release-to-release or downgrade certification.

See [ADR-030](ADR-030-sqlite-schema-baseline.md), the
[verification guide](../sqlite-migration-verification.md) and
[upgrade matrix](../release-upgrade-matrix.md). The generated API follows
[SQLDelight's migration contract](https://sqldelight.github.io/sqldelight/2.0.2/jvm_sqlite/migrations/);
transaction/retry behavior is exercised on SQLite as described above.
