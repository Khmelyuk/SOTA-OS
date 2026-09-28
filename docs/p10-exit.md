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
| Revoke delegations | Scoped authority update and pending-exit guards | ExitServiceTest: Core isolation, regrant denial, rollback |
| Close relations | Scoped TRUST/AGREEMENT inventory | ExitServiceTest: closure, isolation, rollback |
| Settle obligations | RETAINED responsibility or independently confirmed FULFILLED | ExitServiceTest: retention/rollback; ExitExportTest: authority and evidence |
| Export allowed data | Approved frozen snapshots, ownership/provenance, durable archive | ExitExportTest: default deny, scoped grants, all artifact kinds, edits/restart, cross-Core/person denial |
| Terminate participation | Matching delivered digest then membership revocation | ExitServiceTest: delivery failure, digest mismatch, audit rollback, repeated completion |
| Preserve autonomy/history | SQLite stages, additive migration, append-only archives/audit/events | ExitServiceTest: restart, migration, historical guards, unaffected Core |

Coverage establishes the local voluntary-exit slice. Full production protocol
coverage still needs general relation/contract inventories, downstream delegation
lineage. Signed P09 event production is available through the opt-in host below.

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
mid-flight by rewriting its history. The default local CLI remains unsigned and
requires no signing setup. Legacy unsigned records already in the same journal
still fail strict P09 admission; this change does not migrate them. Historical
verification after key rotation/revocation remains a separate P09 limitation.

`SignedExitTest` verifies all six signed stages, causal parents, two-node production
admission, lost acknowledgement/restart/retry, remote membership isolation,
transaction rollback, key failures, mode/origin continuity and peer-context denial.

Full JDK 21 build on 2026-09-28: 115 tests passed, zero failures/errors/skips;
Detekt and migration verification passed.
