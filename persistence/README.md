# persistence module

The first SQLite vertical slice is defined in `src/main/sqldelight/` and
implemented by `SqlDelightRepositories`. The adapter bundle implements all
MVP repository ports and receives the generated `SotaOsDatabase`, keeping
storage behind the application-owned interfaces.

## Opening a database

Create a `SqlDriver` for the local database file, create the schema once for
a new database with `SotaOsDatabase.Schema.create(driver)`, then pass the
driver to `SqlDelightStore`. For an existing database, use its migration
path before constructing the store. Close the store when the node shuts
down. The caller is responsible for configuring the driver to enforce
SQLite foreign keys.

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

The SQLDelight schema is the current MVP schema. `SqlDelightStore` applies
idempotent additive table creation for RIGHT and CONSENT so existing database
files can open with these records available. Other future schema changes
still need versioned SQLDelight migrations.

Two frozen Git schema profiles now exercise this opening path with synthetic
historical governance, memory and credential records. Seven tests cover exact row
preservation, repeated reopen, structural comparison with a fresh store, current
service behavior, guards and retry after a failed baseline insertion. The entire
compatibility sequence is not a single schema transaction. See
[historical migration acceptance](../docs/persistence-migrations.md) for evidence,
fixture provenance and remaining runtime-schema coverage.

`actor_signing_key` stores one active Ed25519 public key per actor as Base64
X.509 material; private keys are never persisted. Lifecycle changes are
recorded in append-only `actor_signing_key_audit` with explicit timestamps and
`ENROLL`, `ROTATE`, or `REVOKE` operations. SQLite triggers reject direct
UPDATE/DELETE attempts. Load records with `ActorKeyDirectory.fromRecords(...)`
before constructing `EventSignatureAdmission`.
