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
lineage and signed P09 producers.

## Verification — 2026-09-26

Full JDK 21 build, Detekt and migration verification passed: 109 tests, zero
failures/errors/skips (14 P10 SQLite tests). A real CLI smoke run on a temporary
database verified authentication, rejected confirmation without mutation, successful
selected-Core exit, other membership and credentials retained, archive SHA-256 and
0600 file permissions. No user database was modified during verification.
