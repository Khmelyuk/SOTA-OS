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
- Membership rows use a deterministic key derived from subject, collective,
  role, and start time, so state changes update the same membership record.

The SQLDelight schema is the current MVP schema. `SqlDelightStore` applies
idempotent additive table creation for RIGHT and CONSENT so existing database
files can open with these records available. Other future schema changes
still need versioned SQLDelight migrations.
