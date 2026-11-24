# Historical SQLite migration acceptance

Updated: 2026-11-24

The migration suite adds two frozen declared-schema baselines and one captured
runtime profile to the existing
targeted compatibility tests. It addresses the risk that tests constructed by
removing columns from today's schema can follow today's assumptions instead of
preserving a genuinely older layout. Existing credential, historical-signature and
Decision-specific migration tests remain in place.

The baselines are [durable P09](https://github.com/Khmelyuk/SOTA-OS/commit/ece3ad113fef41be69e632117304e28bfca02708)
and [Knowledge versions](https://github.com/Khmelyuk/SOTA-OS/commit/3c1a4aa1d20cf77179e3c37580ddd682b329d018).
Their resource manifests retain source paths/fingerprints and are themselves pinned
by SHA-256. Synthetic seed rows do not use current serializers or repositories.
The fixture catalog and authoring procedure are in
[test resources](../test/src/test/resources/migrations/README.md).

## Acceptance evidence

`HistoricalMigrationTest` verifies:

- Healthy, populated historical databases before current store initialization,
  including absence of columns that did not yet exist.
- Every original table's columns and rows remain byte-for-byte equal as returned
  by SQLite after upgrade and three reopens. Added columns are checked separately;
  raw JSON text, historical IDs, timestamps, journal positions and cursors survive.
- Table names, column types/nullability/defaults/primary-key positions and foreign-key
  edges match a freshly initialized current store. This comparison does not claim
  complete equivalence of indexes, CHECK clauses or trigger SQL text.
- SQLite integrity and foreign-key checks pass. No Decision snapshot, delegation
  parent, signed verification receipt, historical approval or trusted peer is invented.
- A failed credential-baseline insertion prevents store construction, preserves
  original data and can be retried after removing the injected failure. Reopen
  creates only one unattributed LEGACY_BASELINE record where appropriate.

`HistoricalMigrationBehaviorTest` exercises the upgraded store through current
application services and repositories:

- Legacy unbound Decisions cannot execute; fresh bound Decisions execute in the
  stored PROTECTION mode. Historical Knowledge versions can acquire a new candidate
  without changing their predecessor chain or old content.
- Unsigned Event/journal history is retained exactly and cannot become a signed
  P07 source merely by opening the database.
- An older credential keeps its lockout and verifier, then authenticates after
  unlock; a newer revoked credential remains revoked with its original revision
  and operator audit. Legacy baselines do not fabricate an operator identity.
- Event/journal/audit immutability, irreversible consent withdrawal, immutable
  authority/Decision links and Knowledge content guards apply after migration.
  A revoked child authority keeps its recorded parent and remains unusable.

These tests run as part of the ordinary JDK 21 `./gradlew build`, including Detekt
and the existing SQLDelight migration verification task. They need neither Git
history nor Node at test time. The Node exporter is only a fixture-authoring tool.

## Historical runtime and signed history

The `runtime-signed` profile was captured from an isolated build of `3c1a4aa`,
using that revision's SqlDelightStore, P09 services, codecs and test helpers. Its
38 tables, two indexes and 50 triggers include guards installed by Kotlin startup.
The 27 synthetic rows include key retirement, an active key, peer rotation and
revocation, provisioning audit, a locally verified receipt and historical approvals.
No production code was changed in the archived build; an authoring main and a
JavaExec task were added. The [capture recipe](../scripts/historical-runtime/README.md)
records the procedure and public bearer substitutions. Private signing keys are
never exported. The frozen manifests and all four signed vectors have SHA-256 pins.

Six tests in `RuntimeSchemaMigrationTest` and `RuntimeSignedMigrationTest` verify:

- Original columns/rows and all 50 trigger definitions survive upgrade and three
  reopens. Populated key/provisioning/receipt/approval audit tables reject updates
  and deletes both before and after upgrade. SQLite checks and current column/FK
  shape comparison pass; full table/index/CHECK text equivalence is not claimed.
- Exact replay retains the pre-retirement receipt, original key and verification
  time. Changed records and a cryptographically valid but unknown old-key record
  remain denied without writes.
- An independent exact-record approval still permits import; its receipt records
  the original approval target and the new verification time. An injected receipt
  insertion failure rolls back Event, journal and checkpoint changes, and retry
  succeeds after removing the fault.
- A revoked approval remains denied and gains no receipt or Event during opening.
  Active key/peer state, peer revisions and revoked-peer authentication denial
  survive restart. A retained receipt cannot bypass live peer authentication.

## Operational and coverage limits

SqlDelightStore currently applies additive compatibility steps during opening.
The entire sequence is not a single schema transaction: successful earlier DDL can
remain after a later step fails. The failure test proves preservation and safe retry
for the injected baseline-write failure, not universal recovery from arbitrary
malformed databases, storage exhaustion or process death at every statement.

These are synthetic acceptance profiles, not production database backups. The
runtime profile covers one historical revision and a bounded P09 history. It does
not exercise every captured trigger, P10 history or every provisioning transition.
Verification receipts remain local to their original node; fixture restoration is
not authorization to copy trust evidence between production nodes.

No production migration code, schema or governance policy is changed by this
increment. Versioned `.sqm` release migrations and broader opening-failure coverage
remain open. The next independent persistence increment is populated historical
P10 exit/retained-obligation acceptance, with preservation, restart and failure
checks. P09 operational lifecycle remains the next functional track.
