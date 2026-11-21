# Development roadmap — updated 2026-11-21

Published baseline before this increment: GitHub `main` at `8936075`
(persisted P07 sources and current states), with successful CI. The earlier integration
review started at `1f6d175` (human-invoked Research CLI); the local checkout had
been based on `ece3ad1` and was missing 26 upstream commits. The roadmap now
tracks the integrated implementation and subsequent bounded increments.

## Implemented baseline

- P04 parent references, ancestor validation, atomic cascades and durable
  revocation audit; P05/P08/P09/P10 consumers check ancestry (ADR-013).
- Durable P10 self-exit, governed export, typed relation inventory and retained
  obligations; optional signed CLI exit and strict P09 delivery (ADR-010/014).
- Ed25519 signing, governed peer/key admission, exact historical evidence,
  bounded HTTPS hosting, background retry/shutdown, metrics and staged material
  rotation (ADR-003a/011/012/015/016/017).
- Signed P05 Action/Result and P07 Experience/Knowledge producers, including
  human validation/deprecation, and a reproducible two-node HTTPS rehearsal.
- P08 persisted invitations, target-collective issuance, exact-record lifecycle
  checks, scoped removal and atomic Core/founder creation (ADR-020).
- Opt-in authenticated Core invitation/acceptance with explicit representative
  mandate, unit/recipient scope, current lineage and atomic human audit (ADR-021).
- P05 decisions and execution require a persisted ACTIVE Mission, rechecked at
  execution; decision policy receives its stored ID (ADR-022). Historical Result
  reporting remains available after Mission closure.
- Ordinary P07 verifies exact persisted sources and current Knowledge lifecycle
  states inside a shared transaction, with rollback after failed writes (ADR-023).
- P05 Action/Event mode comes from the current active Mission at execution;
  Result recording checks Action mode against new signed evidence, with legacy compatibility (ADR-024).
- JDK 21 CI with module graph checks, Detekt, tests and CLI smoke scenarios.
- Local credential lifecycle and a human-invoked, candidate-only Research CLI
  (ADR-018). Research draft approval does not publish externally.

The source criterion extracts and fingerprints are already available in
[AC-12/13/17/18 mapping](ac-p09-mapping.md). The original PDFs remain external;
the criteria are no longer missing from the repository's mapping.

## Work continued during integration

Fourteen local authorization regressions were adapted to the current contracts.
The upstream implementation already met the delegation ceiling, stored-issuer
revocation and idempotent cascade checks. Two remaining failures were reproduced:
a root could issue an already expired grant, and a Person could persist a
Decision referencing another Person's authority. Both now fail before writes.
Delegation policy evaluation also receives the selected persisted parent.

No alternate local P10 implementation replaces the newer upstream lifecycle.
The earlier local version is retained in a local checkpoint branch. Its planned
ADR-010 number is not reused: upstream ADR-010 already defines the P10 contract.

## Next increments and completion criteria

| Order | Increment | Evidence required before completion |
|---|---|---|
| Done 2026-11-18 | Signed P07 Experience/Knowledge producers | Exact persisted source checks, full signed snapshots, causal parents, local non-Agent governance, atomic entity/Event/journal/receipt writes; SignedMemory acceptance tests and ten-record HTTPS rehearsal. Host authentication remains required; see ADR-019 |
| 2 — in progress | Remaining command authorization and source conformance | Command audit, persisted P08 admission, authenticated delegated Core invitation/acceptance, active persisted P05 Missions, ordinary P07 source/state boundary and P05 mode propagation implemented under ADR-020/021/022/023/024. Remaining: initial collective mandate/root eligibility, broader representation, P05 participation/version/mode-change governance and P07 validation mandates/versioning/lifecycle audit |
| 3 | P09 operational lifecycle | Governed certificate renewal and configuration changes, old/new material recovery, failure diagnostics and deployment runbooks; automated historical attestations only with explicit provenance policy |
| 4 | Extended P10 relations and settlement | Multiparty consent and additional settlement rules from source requirements, retained obligations, failure/restart coverage, and explicit collective scope |
| 5 | Broader persistence and release acceptance | Versioned migration fixtures, missing conformance tests, review of public API/GUI scope, and recorded CI/rehearsal evidence for the selected release |

P07 now uses the existing P05/P10 signed-runtime pattern. Research remains
separate from canonical P06/P09 history. Receiving a fact does not validate
Knowledge, materialize remote memory entities or execute an Action. The no-Agent
rule and exact-source boundary are documented in [signed memory](p07-signed-memory.md).

The integration closes a bounded authorization increment. It does not claim
that all Phase 3 command rules, public deployment operations or general contract
settlement are complete. See [review follow-up](review-follow-up.md) for the
existing source review backlog.

The [command authorization audit](command-authorization.md) maps each command
group to its actor/authority checks and executable evidence. The next bounded
review is initial collective mandate/root standing and broader representation,
followed by P05 participation/version/mode-change governance and P07 validation mandates. ADR-021 records
the original Protocol and Security PDF constraints reviewed for the bounded
admission host; it does not claim to define the missing collective decision protocol.

The original specifications do not select who approves an initial mandate in an
existing Core or its quorum. That policy question is pending the owner's answer;
no voting rule is inferred. Independent P05 Mission existence/state work is
implemented under [ADR-022](adr/ADR-022-active-mission-boundary.md), with source
sections and six SQLite regressions. P07 validation/source review can proceed
independently of the unresolved root policy.

Ordinary `MemoryService` now compares Event/Experience/Knowledge inputs with their
current persisted records and rejects stale transitions under ADR-023. Nine new
regressions cover source/state checks, restart and transactional rollback.
General validation mandates remain a separate policy question; exact-source checks
do not grant them.

P05 mode propagation is implemented under ADR-024: execute uses current persisted
Mission.mode, and new signed Action facts bind it. Decision mode/version binding
is still open; no public mode-change command or protection-specific policy is added.

Next independent slice: ordinary P07 lifecycle audit and deprecation-reason
retention. Map the original Memory integrity/governance requirements to atomic
local events and restart/rollback tests before extending the producer. Signed P07
already records lifecycle facts. Version lineage and general canonicalization
mandates remain separate requirements.
