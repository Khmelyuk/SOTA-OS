# persistence module

The first SQLite vertical slice is defined in `src/main/sqldelight/` and
implemented by `SqlDelightRepositories`. The adapter bundle implements all
MVP repository ports and receives the generated `SotaOsDatabase`, keeping
storage behind the application-owned interfaces.

## Opening a database

Create a `SqlDriver`, enable SQLite foreign keys, then construct `SqlDelightStore`.
The store now owns fresh schema creation and legacy compatibility initialization.
It reads `PRAGMA user_version` before schema writes: unversioned `0` and baseline
`1` are accepted, all other numbers are rejected. A new empty catalog at `0` gets
the generated schema transactionally; existing catalogs must contain the core
tables. Only completed compatibility initialization marks a legacy database `1`.
See [ADR-030](../docs/adr/ADR-030-sqlite-schema-baseline.md) for the opening inventory
and limits. The number is not a full schema fingerprint or a release version.

Close the store when the node shuts down. The caller owns the driver and must also
close it if construction fails. It remains responsible for configuring foreign keys.
Production callers should not invoke generated `Schema.create` themselves; direct
generated database/repository access does not apply the store's opening contract.

`SqlDelightRepositories(store.database)` provides the repository instances
for application-service wiring. JSON columns use explicit mappings in
`JsonMapping`; sets are encoded in sorted order for stable serialization.

## Integrity properties

- Event writes go through `EventStore.append`; duplicate event IDs are
  rejected, and SQLite triggers reject direct `UPDATE` and `DELETE` attempts.
- Authority and knowledge rows are inserted once and updated by key when
  their domain services change lifecycle/governance state. Consent rows keep
  their original grant terms and only update the revocation timestamp.
  RIGHT records are inserted once.
- Knowledge repository saves enforce immutable version content/provenance/links;
  only status changes on an existing ID. New versions check known ancestry and
  reject duplicate local successors inside the transaction. Raw SQL remains a
  trusted boundary; see [ADR-026](../docs/adr/ADR-026-knowledge-version-lineage.md).
- New Decisions store their exact Mission snapshot in the same INSERT. The binding
  and Mission reference cannot be updated. Opening an older database adds a nullable
  snapshot column and leaves historical records unbound; new execution requires a
  fresh Decision. See [ADR-027](../docs/adr/ADR-027-decision-mission-snapshot.md).
- Membership rows use a deterministic key derived from subject, collective,
  role, and start time. Lookup uses those exact terms, including a nullable role,
  and lifecycle changes preserve the actual stored ID, including legacy IDs.
  Ambiguous duplicate terms are rejected. P08 persists invitations and compares
  the full current record before transitions; see [ADR-020](../docs/adr/ADR-020-persisted-membership-admission.md).

The SQLDelight schema is the current MVP declaration. `SqlDelightStore` additionally applies
idempotent additive table creation for RIGHT and CONSENT so existing database
files can open with these records available. Other future schema changes
still need versioned SQLDelight migrations. The baseline marker does not dispatch
`.sqm` migrations; generated declarations and runtime guards must first be reconciled.

Two frozen Git schema profiles now exercise this opening path with synthetic
historical governance, memory and credential records. Seven tests cover exact row
preservation, repeated reopen, structural comparison with a fresh store, current
service behavior, guards and retry after a failed baseline insertion. The entire
compatibility sequence is not a single schema transaction. See
[historical migration acceptance](../docs/persistence-migrations.md) for evidence,
fixture provenance and remaining runtime-schema coverage.

A third profile captures an actual historical runtime: 38 tables, two indexes,
50 startup triggers and synthetic P09 key/peer/receipt/approval history. Six further
tests retain all captured guard definitions, check populated audit immutability,
preserve exact signed replay and revocations, and exercise rollback/retry of an
approved historical import after upgrade. These frozen profiles are unchanged;
current bootstrap version handling is defined by ADR-030.

A fourth profile captures 68 rows of historical P10 state: completed signed exit,
approved portable snapshots, typed relation terms, retained/fulfilled obligations,
an unsigned pending exit and an unaffected Core. Seven tests cover exact preservation,
restart/resumption, archive and termination rollback/retry, and independent fulfillment
after exit. Snapshot restoration installs old triggers after loading rows, with
foreign keys checked at commit; this is test setup, not a production migration change.

The focused `:test:migrationAcceptance` task now runs 45 tests. Twelve opening
interruptions retain legacy version `0` until successful retry; four source-profile
tests cover version rejection without byte changes and upgrade through CLI `init`.
Seven additional API tests exercise the shared CLI opening boundary on fresh, unrelated
and versioned databases. Existing schema/behavior and P09/P10 history checks remain.

Seven canonical-catalog tests pin 38 tables, two explicit indexes and 51 triggers
from the completed `430bde9` runtime. Generated declarations alone contain only ten
triggers; the other 41 remain explicit runtime responsibilities. Tests compare the
exact fresh catalog and historical index/guard text, with six separately pinned
historical formatting variants. See the [inventory](../docs/sqlite-schema-inventory.md)
for the generator experiment, precise coverage limits and next migration-verification step.

An empty, SHA-256-pinned `src/main/sqldelight/databases/1.db` now anchors the actual
SQLDelight migration comparison in `check`; missing or modified baseline bytes fail.
Candidate generation writes separately under `build/schema-candidates`. Two tests
inspect disposable copies and verify full runtime completion with preserved seeded data.
The [verification guide](../docs/sqlite-migration-verification.md) records the isolated
positive/negative canary, build-only comparator override and required production ordering.
No numbered production migration or version-two acceptance is enabled yet.

`actor_signing_key` stores one active Ed25519 public key per actor as Base64
X.509 material; private keys are never persisted. Lifecycle changes are
recorded in append-only `actor_signing_key_audit` with explicit timestamps and
`ENROLL`, `ROTATE`, or `REVOKE` operations. SQLite triggers reject direct
UPDATE/DELETE attempts. Load records with `ActorKeyDirectory.fromRecords(...)`
before constructing `EventSignatureAdmission`.
