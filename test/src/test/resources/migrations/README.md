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
The third captures an actual old runtime with synthetic P09 history; it is not a
production database backup. All 50 captured trigger definitions are retained across
upgrade; behavior checks exercise populated key/provisioning/receipt/approval audit
tables, not every trigger. Captured history is node-local evidence and must not be
treated as a portable trust grant. Additional historical versions, populated P10
history, versioned `.sqm` migrations and broader opening failures remain separate
coverage. See [migration acceptance](../../../../../docs/persistence-migrations.md).
