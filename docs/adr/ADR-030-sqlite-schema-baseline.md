# ADR-030 — SQLite schema baseline and opening boundary

Status: ACCEPTED
Date: 2026-11-26
Level: Implementation Architecture

## Context

The historical acceptance matrix covers four pinned profiles, but all previously
opened databases retained SQLite `user_version = 0`. Gradle's `0.1.0-mvp` artifact
version is not a schema identifier. CLI `withStore` used file size to decide whether
to create generated tables, then `SqlDelightStore` ran additive compatibility steps.
A later binary could therefore be opened by this code without a version boundary.

## Decision

`SqlDelightStore` owns fresh creation, version inspection and completion marking.
It reads `PRAGMA user_version` before any schema writes. Version `0` is the legacy
unversioned input; `1` is this application's completed runtime baseline. Every other
value, including negative values, throws `UnsupportedSchemaVersionException` before
DDL or data writes. The exception exposes the version, not database contents.

An empty catalog at version `0` receives the generated SQLDelight schema inside a
transaction. An existing database must have the twelve core tables: person, identity,
core, membership, authority, mission, decision, action, result, event, experience
and knowledge. An empty versioned database or a layout missing any of these tables
is rejected before compatibility writes. This is a bounded structural check, not a
complete schema fingerprint, integrity proof or authorization decision.

After preparation, the existing additive compatibility steps run unchanged. Only
after all steps succeed does legacy `0` become `1`. If an earlier compatibility step
or the final marker write fails, the store is not returned. Completed earlier DDL
may remain at version `0`; a new opening retries those idempotent steps. Opening an
existing baseline `1` still applies those checks and does not rewrite the marker.
The compatibility sequence as a whole is not made transactional by this change.

The marker covers runtime-installed guards and compatibility structures in addition
to generated declarations. It does not claim a named release, independently certify
database content or enable SQLDelight `.sqm` migration dispatch. A future numbered
migration must explicitly reconcile the generated schema and runtime baseline before
advancing this version. Do not edit a version marker to bypass a rejection.

## Opening-path inventory

| Entry point | Database ownership and version behavior |
|---|---|
| CLI `init`, `demo` | `Main.kt` → `withStore` → `SqlDelightStore`; fresh/reopen use the same preparation |
| CLI authentication/credentials, consent, exit, research | Command handlers → `withStore`; action receives a store only after successful opening |
| CLI P09 exchange/serve/run | `SyncCommands.kt` → `withStore`; runtime receives the already opened store, including the configured database path |
| P09 local check/compare, CLI help | No store opening; TLS/configuration diagnostics do not migrate databases |
| Application runtimes and repositories | Receive an opened store or generated database; they do not independently create a SQLite driver |
| Integration fixtures and rehearsal nodes | Direct `SqlDelightStore` construction, sometimes preceded by generated `Schema.create`; both reach the same version boundary |
| Historical fixture restoration | Restores pinned raw DDL/rows before constructing the current store; resources and their hashes remain unchanged |
| ResearchBootstrap test | Deliberately uses only generated schema/repositories for service transaction tests; it is not a production opening path or completed runtime baseline |
| JDBC fault/setup helpers and archived fixture-authoring tools | Test/authoring access only; direct SQL is outside the production opening contract |

The CLI continues to select its path, create parent directories, enable foreign
keys and close its driver on success or failure. Direct callers retain the same
foreign-key and driver-lifetime responsibilities. Raw `SotaOsDatabase` construction
or direct SQL is a trusted low-level API and cannot enforce the store boundary.

## Verification and limits

- All four historical profiles reject versions `-1`, `2` and `2147483647` through
  direct construction and the in-process CLI `init` entry point without
  changing file bytes; resetting a test fixture to its original `0` permits upgrade
  to `1`, preserving every historical row across three reopens.
- Twelve opening interruptions cover each profile after Decision DDL, after the
  credential-baseline statement and before the version marker. Failed legacy
  opening stays at `0`; retry and three reopens reach `1` and the exact rows,
  table/column/FK shape and guards of an uninterrupted upgrade.
- CLI opening covers absent files, zero-length files and empty SQLite catalogs;
  newer populated databases, unknown versions with no tables, unrelated unversioned
  data and an empty versioned database are rejected before invoking the action.
  Rejection checks compare the complete file bytes.
- Catalog inspection distinguishes tables from same-named triggers and excludes
  only SQLite's literal `sqlite_` prefix; a user table named `sqliteXdata` does not
  make an unrelated database appear empty.

These are stopped, local SQLite fixtures in the default journal mode. This is not
cross-process migration coordination, a WAL recovery test, arbitrary malformed-schema
validation, power-loss recovery or downgrade support. Older binaries predating this
ADR ignore the marker; the change cannot retroactively prevent their use. Backups,
matching binaries and explicit release evidence remain necessary for rollback plans.

The next increment is a canonical version-one schema inventory (generated tables
plus runtime guards), followed by a verified numbered migration path. See the
[upgrade matrix](../release-upgrade-matrix.md) for supported profiles and open paths.
