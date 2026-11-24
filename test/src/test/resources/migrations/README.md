# Frozen historical SQLite fixtures

These resources contain declared SQLDelight DDL and a captured historical runtime
schema from immutable Git revisions, with synthetic rows. Historical fixture construction
never calls the current `SotaOsDatabase.Schema.create`, domain encoders or current
repositories. The current schema is used only to construct the fresh comparison target.

| Directory | Source commit | Declared DDL |
|---|---|---|
| durable-p09 | `ece3ad113fef41be69e632117304e28bfca02708` | 2 source files, 25 statements; before delegation ancestry, credential lifecycle and Decision snapshots |
| knowledge-versions | `3c1a4aa1d20cf77179e3c37580ddd682b329d018` | 9 source files, 48 statements; after Knowledge revision, before Decision snapshots |
| runtime-signed | `3c1a4aa1d20cf77179e3c37580ddd682b329d018` | Actual runtime: 38 tables, 2 indexes, 50 triggers; 27 synthetic P09 history/provisioning rows |
| runtime-p10 | `3c1a4aa1d20cf77179e3c37580ddd682b329d018` | Actual runtime: 38 tables, 2 indexes, 50 triggers; 68 rows with completed signed exit, unsigned pending exit and an unaffected Core |

Each `schema.json` records the full commit, original source paths and their SHA-256
digests. DDL text is retained; tables execute before indexes and triggers to resolve
cross-file dependencies. SQLDelight parameterized queries are excluded. Each
`data.json` is synthetic fixture data, with Unicode, newlines, nulls, historical IDs,
unsigned journal bytes, transport cursors, Knowledge predecessor links, revoked
consent and local credential state. The newer profile also contains an explicitly
revoked delegation and credential lifecycle audit.

The known passphrase `historical-test-passphrase` and fixed salt/verifier are public
test vectors only. No live credentials, private signing keys or user databases are
included. The old unsigned journal entry deliberately has no verification receipt.

`HistoricalSchema` pins SHA-256 for both files in each directory; the loader rejects
changed bytes. Tests run entirely from classpath resources with JVM/SQLite. Git,
Node and access to repository history are not needed in CI.

The `runtime-signed` profile captures `sqlite_master` and raw rows from an isolated
build of the old runtime. Its manifest fingerprints the old persistence sources,
test helpers and the authoring program. `vectors.json` contains four old-runtime
canonical signed records: received before key retirement, independently approved,
approval revoked, and neither received nor approved. It has a separate pinned hash
in `RuntimeHistorySupport`. Six tests check preservation, old guards, current schema
shape, exact replay, denials, approval import rollback/retry and peer/key lifecycle.
The public bearer vectors are `public-historical-peer-token` and
`public-retired-peer-token`; only their hashes are stored in the fixture database.
No private keys are exported. See the
[capture recipe](../../../../../scripts/historical-runtime/README.md).

The `runtime-p10` profile retains a completed signed exit, its exact archive and
approved Event/Knowledge/Evidence snapshots, typed Trust/Agreement participation,
retained and fulfilled obligations, an unsigned REQUESTED exit and a third unaffected
Core. Seven tests cover preservation, signed history, idempotent resumption, archive
and termination failures, and independently governed fulfillment after exit. Its
`vectors.json` pins process IDs and six canonical signed records; the hash is in
`P10HistorySupport`. See the [P10 capture recipe](../../../../../scripts/historical-p10/README.md).

Snapshot restoration now creates structures, inserts raw rows with deferred foreign-key
checks and installs captured triggers in one transaction. Foreign keys remain enabled
and are checked at commit. This permits restoring an existing pending exit with its
still-active membership without replaying those rows as new commitments. Every guard
is installed before current store opening; pre-upgrade tests verify guard behavior.

## Adding another historical baseline

From the repository root, run the authoring tool with a full commit SHA and a new
directory name:

```bash
node scripts/export-migration-fixture.mjs <full-commit-sha> <new-fixture-name>
```

The tool reads only that revision and refuses to overwrite an existing directory.
Add reviewed synthetic `data.json`, pin both resource hashes in HistoricalSchema,
and specify the profile's expected historical semantics in the migration tests.
Existing fixtures stay frozen when current schemas or codecs change.

## Scope

The first two profiles contain historical declarations and separately authored rows.
The third and fourth capture an actual old runtime with synthetic P09 and P10 history;
neither is a production database backup. All 50 captured trigger definitions are
retained across upgrade; behavior checks exercise populated audit, exit and relation
tables, not every trigger. Captured history is node-local evidence and must not be
treated as a portable trust grant. Additional historical versions, further P10
states, versioned `.sqm` migrations and broader opening failures remain separate
coverage. See [migration acceptance](../../../../../docs/persistence-migrations.md).
