# P10 local exit and AC-18 evidence

AC-18: **Користувач може вийти з Core**. Sources and fingerprints are in
[the criteria mapping](ac-p09-mapping.md); decisions and limits are in
[ADR-010](adr/ADR-010-exit-protocol.md).

```bash
./gradlew :api:run --args='exit leave --handle HANDLE --core CORE_ID --out /private/path/exit.json --db /path/sota-os.db'
```

Authenticate with the local provider and type `ВИХОДЖУ З CORE CORE_ID`
when the CLI presents the actual Core ID. Output must be a new path in an
existing directory. A failed write does not terminate membership; rerunning
with a new path resumes the open exit. Earlier revocations/closures remain committed.
Unfulfilled obligations remain recorded as responsibility after leaving.

Local hosts may use `api.exit.P10Runtime` with authenticated actors and an appropriate
RightsConstraint. Trusted adapters populate `ExitInventoryRepository` with scoped
relations, obligations and owned evidence. Export approval requires an independent
human operator with `exit.export`, `core:CORE_ID` and `EVENT:id`, `KNOWLEDGE:id` or
`EVIDENCE:id` scope. Fulfillment requires `exit.settle`, `core:CORE_ID`,
`obligation:id` and evidence.

| Protocol step | Implementation | Automated evidence |
|---|---|---|
| Request and verify authority | Authenticated CLI; self actor, active membership, RightsConstraint | ExitServiceTest: other actor, incomplete stages, policy denial |
| Revoke delegations | Core-scoped authority subtree updates, immutable lineage, pending-ancestor guards | DelegationCascadeTest: descendants, independent branches, Core isolation, restart and atomic audit |
| Close relations | Typed immutable Trust/Agreement terms and Core-scoped participation | RelationInventoryTest: persistence, closure, isolation, rollback |
| Settle obligations | RETAINED responsibility or independently confirmed FULFILLED | ExitServiceTest: retention/rollback; ExitExportTest: authority and evidence |
| Export allowed data | Approved frozen snapshots, ownership/provenance, durable archive | ExitExportTest: default deny, scoped grants, all artifact kinds, edits/restart, cross-Core/person denial |
| Terminate participation | Matching delivered digest then membership revocation | ExitServiceTest: delivery failure, digest mismatch, audit rollback, repeated completion |
| Preserve autonomy/history | SQLite stages, additive migration, append-only archives/audit/events | ExitServiceTest: restart, migration, historical guards, unaffected Core |

Coverage establishes the local voluntary-exit slice. Full production protocol
coverage still needs multiparty consent and additional contract policies. Typed local
relation inventory is implemented under [ADR-014](adr/ADR-014-relation-inventory.md). Recorded delegation
chains now cascade under [ADR-013](adr/ADR-013-delegation-lineage.md); legacy
unknown ancestry cannot be reconstructed. Signed P09 event production is available
through the opt-in host below.

## Verification — 2026-09-26

Full JDK 21 build, Detekt and migration verification passed: 109 tests, zero
failures/errors/skips (14 P10 SQLite tests). A real CLI smoke run on a temporary
database verified authentication, rejected confirmation without mutation, successful
selected-Core exit, other membership and credentials retained, archive SHA-256 and
0600 file permissions. No user database was modified during verification.

## Signed P09 producer — 2026-09-28

Hosts may opt into `api.exit.SignedP10Runtime`. It exposes `exit` (the existing
P10 facade) and `sync` (production P09), sharing one SQLite store. Supply the local
node/allowed actors, governance context, clock, IDs, RightsConstraint and an
actor-bound signer provider. The host authenticates callers and retains private
keys securely across restarts; this composition does not generate or store them.
Use `sync.keyProvisioning` and `sync.peerProvisioning` with independently authorized
operators to provision public keys and sharing policies before exchange.

Each new P10 transition is signed before first persistence. Its sync record names
the immediately preceding transition as a causal parent. Signature verification
uses the actor's current SQLite public key, not the supplied signer's self-check.
The transition, mutation, audit, signed event and sync journal commit together.
Missing/revoked/mismatched keys or a journal failure roll back the whole stage.
No network connection is needed to advance a locally configured signed exit.

Peer policies must explicitly allow the origin, actor, direction and exact context
`Context("core-exit", description = "core:CORE_ID")`. Event payloads contain only
exit/Core/Person/stage identifiers; portable archive contents are not synchronized.
These records are historical facts with no entity assertions: receipt of a remote
exit does not itself revoke local membership or authority.

Restart with the same origin and signer configuration. A signed exit cannot
silently resume through the unsigned CLI, and an unsigned exit cannot be converted
mid-flight by rewriting its history. The default local CLI remains unsigned and requires no signing setup.
Explicit signed CLI mode is described below. Legacy unsigned records already in the same journal
still fail strict P09 admission; this change does not migrate them. New signed P10 records receive local verification receipts under ADR-012.
Exact prior records remain verifiable after rotation/revocation; unknown old-key
records and pre-receipt journal migration remain fail-closed.

`SignedExitTest` verifies all six signed stages, causal parents, two-node production
admission, lost acknowledgement/restart/retry, remote membership isolation,
transaction rollback, key failures, mode/origin continuity and peer-context denial.

Full JDK 21 build on 2026-09-28: 115 tests passed, zero failures/errors/skips;
Detekt and migration verification passed.

## Cascading delegations — 2026-09-29

P10 now revokes every recorded descendant of directly held/issued target-Core
authorities, with append-only per-authority audit in the exit transaction. Other
Core chains and independent branches remain active. Pending exits block new
descendants before revocation executes. See ADR-013 for grants, validity, scope
ceilings, migration and consumption checks. Full build: 139 tests passed.

## Typed relation inventory — 2026-09-30

Trusted adapters can use `SqlDelightRelationInventory(store.database)` to import
established outgoing Trust or Agreement participation. `registerAgreement` requires
an explicit `AgreementExitPolicy.WITHDRAW_PARTICIPATION_RETAIN_OBLIGATIONS` and a
list of new Core-scoped obligations. The host verifies agreement consent and policy
compatibility before import; this infrastructure port is not a public enrollment API.

Terms, inventory and obligations commit atomically. Reads expose immutable original
terms separately from ACTIVE/CLOSED participation and current obligation states.
P10 closes participation and retains outstanding duties; independently authorized
fulfillment remains available through `runtime.governance`. Portable obligations
include their relation ID without automatically exporting shared terms.
Legacy rows are preserved without fabricated terms. See ADR-014 for boundaries.

Full JDK 21 build, Detekt and migration verification passed: 147 tests, zero
failures/errors/skips, including eight typed relation inventory tests.

## Signed CLI exit

Use an already provisioned database and an actor-owned Ed25519 private key in a
PKCS12 store. Its active public key must already be enrolled for the authenticated
Person through governed P09 key provisioning. The certificate is a key container;
it does not establish identity or authority. CLI startup neither enrolls nor rotates keys.

```bash
./gradlew :api:run --args='exit leave --db /path/node.db --handle alice --core CORE_ID --out /private/new-exit.json --signing-keystore /private/alice.p12 --signing-alias exit --node node-a --governance-context peer-governance'
```

Set `SOTA_P10_SIGNING_PASSWORD` securely in the process environment. The PKCS12
store and key entry use the same password. Local authentication still prompts for
the Person's passphrase in a terminal and requires the exact exit confirmation.
The signing password is separate from the login and TLS passwords; do not supply
private keys or passwords as command-line arguments. Protect the keystore locally.

All four signing options (`signing-keystore`, `signing-alias`, `node`,
`governance-context`) are required together, with an explicit existing `--db`.
An Ed25519 key, certificate and matching active SQLite actor key are mandatory.
Wrong passwords, wrong algorithms, missing or mismatched actor keys fail before
any exit stage. The password character buffer is cleared after loading; the
private key exists in JVM memory for the CLI invocation.

Restart with the same database, authenticated Person and origin node, and an
appropriate currently enrolled signer. A failed archive delivery leaves the exit
resumable and membership active. Use a fresh output path: existing files are
never overwritten, and a failure after delivery can leave an archive on disk.
Signed/unsigned mode and origin cannot be changed for an in-progress exit.
Each new stage rechecks the current actor key and commits its signed event,
verification receipt, journal record and exit mutation atomically.

This CLI is a standalone process. Stop `sync run` before opening the same SQLite
database here, then restart it to send the new events. The in-process store gate
does not coordinate separate processes. Archive content is not sent over P09;
only signed stage facts are shared under the configured peer/context policy.
Legacy unsigned journal entries are not rewritten and may still block strict export.

`P10SigningKeysTest` covers loader binding, password/alias/algorithm rejection,
reopen/resume and key revocation. `SignedExitTest` retains production P09 recovery
coverage. `scripts/p10-signed-cli-smoke.py` uses a temporary database and keys to
test terminal authentication/confirmation, archive collision, restart, mode/origin
guards, six independently verified signatures, Core isolation and private export.
Run it from the repo root after `./gradlew :api:distZip` with JDK 21 `JAVA_HOME`,
Python 3 and OpenSSL available. Direct key insertion in that fixture is test-only.

Verification — 2026-10-01: full JDK 21 build, Detekt and migration checks passed;
190 tests passed with no failures/errors/skips. Signed CLI smoke passed.
