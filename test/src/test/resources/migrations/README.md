# Frozen historical SQLite fixtures

These resources contain declared SQLDelight DDL captured from immutable Git
revisions and separately authored synthetic rows. Historical fixture construction
never calls the current `SotaOsDatabase.Schema.create`, domain encoders or current
repositories. The current schema is used only to construct the fresh comparison target.

| Directory | Source commit | Declared DDL |
|---|---|---|
| durable-p09 | `ece3ad113fef41be69e632117304e28bfca02708` | 2 source files, 25 statements; before delegation ancestry, credential lifecycle and Decision snapshots |
| knowledge-versions | `3c1a4aa1d20cf77179e3c37580ddd682b329d018` | 9 source files, 48 statements; after Knowledge revision, before Decision snapshots |

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

These are historical **declared-schema** fixtures, not binary exports of old running
nodes. Runtime-only historical guards are not captured from old Kotlin initializers.
Current store opening adds its compatibility structures and guards; tests verify the
result and preservation of the seeded historical rows. Additional runtime snapshots,
signed receipt/key/peer histories and versioned `.sqm` migrations remain separate
coverage. See [migration acceptance](../../../../../docs/persistence-migrations.md).
