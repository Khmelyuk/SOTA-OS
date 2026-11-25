# SQLite upgrade matrix and release acceptance

Updated: 2026-11-25

The currently verified entry points are the four frozen profiles below. They are
identified by source commit and fixture, not by a release number. As of this review,
GitHub has no published releases and the remote has no tags. `0.1.0-mvp` in the root
Gradle build is an artifact version; it does not identify a unique database layout.
No named release-to-release upgrade is yet certified.

The target is the exact checkout running acceptance. Retain its commit SHA, clean
working-tree status and test reports with any release evidence. A passing run does
not establish support for every database made by the source revision: each row is
limited to its pinned schema, synthetic history and exercised behavior. Full source
SHAs and resource hashes are in
[HistoricalSchema](../test/src/test/kotlin/sotaos/test/migration/HistoricalFixture.kt).

## Verified direct upgrades

All four rows open directly through the current `SqlDelightStore`; no intermediate
release or replay of historical commands is used.

| Source revision / profile | Historical state | Required preserved behavior | Acceptance classes |
|---|---|---|---|
| `ece3ad1` / `durable-p09` | Frozen declared DDL; unsigned journal, credential lockout, consent and unbound Decision | Original rows/cursors, unsigned history, lockout; one unattributed credential baseline; legacy Decision cannot execute | `HistoricalMigrationTest`, `HistoricalMigrationBehaviorTest` |
| `3c1a4aa` / `knowledge-versions` | Frozen declared DDL; Knowledge lineage, revoked credential and delegation | Original versions, revoked state and operator audit; no fabricated Mission snapshot or parent mandate | `HistoricalMigrationTest`, `HistoricalMigrationBehaviorTest` |
| `3c1a4aa` / `runtime-signed` | Captured runtime, 50 guards, 27 rows; retired keys, receipts, exact-record approvals and peer lifecycle | Exact signed history/receipts; approved import and retry; unknown/revoked history and revoked peer remain denied | `RuntimeSchemaMigrationTest`, `RuntimeSignedMigrationTest` |
| `3c1a4aa` / `runtime-p10` | Captured runtime, 50 guards, 68 rows; completed signed exit, pending unsigned exit, retained obligations and unrelated Core | Archive bytes, signatures and terms; governed idempotent resumption/fulfillment; unrelated Core preserved | `P10HistoryMigrationTest`, `P10ResumeMigrationTest`, `P10ObligationMigrationTest` |

`OpeningRetryMigrationTest` additionally exercises **each row at both opening
boundaries** below: eight separately named cases. The existing seven classes contain
20 tests, some of which cover more than one profile. Profile construction and vector
checks remain hash-pinned; see [fixture provenance](../test/src/test/resources/migrations/README.md).

## Failure and retry contract

| Boundary | Verified result and retry requirement |
|---|---|
| After successful Decision snapshot-column DDL, before its guard is installed | Construction throws; the new column remains, every original row and historical guard remains intact, integrity/FKs pass. A new driver retries successfully, including the previously unfinished guard installation |
| After successful credential-baseline statement, before later signing-key setup | Construction throws; the completed audit matches uninterrupted migration. Retry adds no duplicate baseline and fabricates no operator attribution |
| Credential-baseline insert rejected by a SQLite trigger (`durable-p09`) | Construction throws, original rows remain, no baseline is inserted; removing the injected trigger permits exactly one baseline |
| Approved signed import receipt insertion rejected (`runtime-signed`) | Event, journal and checkpoint writes roll back; removing the fault permits the independently approved import |
| P10 archive insert, termination Event or obligation audit rejected (`runtime-p10`) | All pre-call rows remain; restart/retry adds each transition once under its existing actor, digest and authority checks |

For the first two boundaries, a test-only driver throws **after** the real SQLite
statement succeeds. The test closes that driver, checks the on-disk state and opens
a new driver. The retried store's complete rows, table/column/FK shape and trigger
definitions must equal an uninterrupted upgrade of the same profile, including
after three further reopens. This verifies partial-progress recovery; it is not a
process-kill, disk-full, filesystem-corruption or power-loss simulation.

Original columns are compared as raw SQLite values, including JSON strings,
timestamps, IDs and journal positions. Existing receipts remain local verification
evidence; neither upgrade nor fixture restoration authorizes transferring them to
another node. Detailed behavioral assertions and schema-comparison limits are in
[migration acceptance](persistence-migrations.md).

## Run and retain evidence

From the repository root with JDK 21:

```bash
git rev-parse HEAD
git status --short
./gradlew :test:migrationAcceptance :test:detekt --continue --console=plain
```

The focused task runs all 28 tests in `sotaos.test.migration` using classpath fixtures
and temporary SQLite files. It needs no Git history, Node, running node or production
database. Its independent HTML report is
`test/build/reports/tests/migrationAcceptance/index.html`; XML results are under
`test/build/test-results/migrationAcceptance/`. Confirm zero failures/errors/skips
and all eight profile/boundary cases before recording this matrix as passing.
Gradle may reuse unchanged results; use `--rerun-tasks` when fresh execution is
required for a release evidence bundle.

The same tests remain in ordinary `./gradlew build` and GitHub CI. The focused task
does not replace the full build, other compatibility tests, CLI smoke or rehearsals
required for a release. Preserve those reports and the artifact digest separately.

## Unverified paths and next implementation

| Path or condition | Current status / evidence needed |
|---|---|
| Named release → named release | Open: select immutable release commits/artifacts and database profiles; give each supported source an explicit row and passing target evidence |
| Versioned `.sqm` migration chain | Open: establish a schema-version baseline and numbered migrations, then verify fresh creation and every declared direct/intermediate upgrade through the actual CLI opening path |
| Unknown, newer or malformed database | No compatibility guarantee or general fail-before-write preflight; design source-version recognition and prove rejection preserves the entire database before broadening support |
| Current database → older binary (downgrade) | Unverified. Additive retry is not a downgrade contract. Recovery planning must use a stopped-node backup and matching binary/configuration; restored state needs separate acceptance |
| Abrupt process death, disk exhaustion, every intermediate statement | Unverified; the two completed-statement interruptions and bounded service failures do not cover these conditions |
| Additional historical states or production-scale data | Open: capture immutable profiles and exercise their semantics, size and recovery constraints independently |

The next bounded engineering step is to define a persisted schema-version baseline
and inventory every opening entry point before adding the first numbered release
migration. Keep compatibility initialization, fresh creation and source-version
rejection consistent; do not infer database compatibility from the Gradle artifact
version. No migration policy, production schema or runtime behavior is changed by
this acceptance increment.
