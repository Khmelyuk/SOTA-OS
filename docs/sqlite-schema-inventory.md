# Canonical SQLite baseline-one inventory

Updated: 2026-11-27

The completed baseline at `430bde9` contains **38 tables, 2 explicit indexes and
51 triggers**. Generated SQLDelight declarations supply all 38 tables, both indexes
and 10 triggers. `SqlDelightStore` supplies the other **41 triggers** before marking
`user_version = 1`. No table or explicit-index definitions change when the fresh
generated schema passes through current compatibility initialization.

The SHA-256-pinned [manifest](../test/src/test/resources/schema-baseline/v1.json)
records all 91 objects as exact ordered `sqlite_master` rows, the immutable source
revision, source fingerprints and both trigger-name lists. This is the canonical
acceptance inventory of the completed runtime, not a claim that generated
`SotaOsDatabase.Schema.create` alone is the complete runtime baseline.

## Runtime-installed guards

| Owning table(s) | Added guards | Protected behavior |
|---|---:|---|
| `authority` | 7 | Parent binding/immutability, lineage terms, ancestor exit and direct exit commitment guards |
| `authority_revocation`, `actor_signing_key_audit` | 4 | Append-only audit |
| `consent`, `decision` | 2 | Irreversible withdrawal and immutable Decision/Mission binding |
| `membership` | 2 | No active membership commitments during pending exit |
| `core_exit_relation`, `exit_obligation` | 8 | Exit commitments and retained typed relation/obligation history |
| `exit_archive`, `exit_transition`, `exit_governance_audit`, `owned_exit_evidence` | 8 | Append-only exit history |
| `relation_terms`, `relation_obligation` | 4 | Immutable relation terms |
| `historical_record_approval`, `historical_record_revocation` | 4 | Append-only historical governance |
| `verified_sync_record` | 2 | Immutable local verification evidence |

Complete names and SQL are in the manifest. Generated triggers protect Event,
sync journal/conflict, local credential audit and provisioning audit histories.
Existing runtime installers remain necessary for older schemas.

## Executable contract

Seven `CanonicalSchemaTest` cases are part of ordinary `build` and the focused
`:test:migrationAcceptance` task (43 historical/schema tests in total):

- Generated schema must equal the pinned full catalog minus exactly the 41 named
  runtime triggers. Creating it alone leaves version `0`; store initialization
  must produce the exact full catalog and version `1`.
- Fresh store creation and repeated initialization must retain the full catalog,
  including table SQL, CHECK/UNIQUE clauses, explicit index SQL and trigger bodies.
  SQLite integrity and FK checks pass. This test compares exact DDL strings.
- The completed baseline must reject signing-key audit updates and deletes while
  preserving the inserted audit row.
- Each of the four historical profiles must have the complete object identity set,
  both explicit index definitions and all 51 guard definitions after upgrade and
  three reopens. Existing row-preservation, shape, governance and retry tests remain.

Historical table SQL is deliberately not compared byte-for-byte with fresh creation:
ALTER history, formatting and column order can differ. Its table/column/FK checks
remain in the earlier migration suite. Full equivalence of historical table CHECK
clauses or implicit autoindexes is not established by this increment.

The two declared historical profiles also retain six reviewed index/trigger text
variants: line breaks/trailing spaces for `exit_one_open`, plus indentation and
keyword case in credential/provisioning audit guards. Their exact strings are
[separately hash-pinned](../test/src/test/resources/schema-baseline/historical-ddl-variants.json).
No general SQL whitespace/case normalization hides changes to literals or conditions.
Both captured runtime profiles match the fresh index/trigger text without variants.

## Numbered-migration boundary

A direct experiment placing all 41 existing definitions into a `.sq` file failed
with the configured SQLDelight 2.0.2 / SQLite 3.18 generator: it reported
`No table found with name NEW` or `OLD` for references in trigger `WHEN` expressions.
The experimental file was removed. The existing executable SQL and governance
conditions are unchanged; no parser suppression or dependency upgrade was introduced.
This is an observed limitation of that experiment, not a claim about all SQLDelight
versions or every possible trigger formulation.

The next increment is to establish SQLDelight's migration-verification baseline and
the ordering contract for numbered migrations while retaining the runtime guard phase.
Generated-only migration verification must not be treated as verification of all 51
guards. Before enabling a new version, check the complete resulting runtime catalog,
historical data preservation and interruption/retry behavior, then write the version
marker last as required by [ADR-030](adr/ADR-030-sqlite-schema-baseline.md).

No production code, declarations, database version or governance policy changed in
this inventory increment. The [capture recipe](../scripts/schema-baseline/README.md)
records reproducibility and artifact limits; the [upgrade matrix](release-upgrade-matrix.md)
retains the supported profile boundaries and unverified release/downgrade paths.
