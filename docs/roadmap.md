# Development roadmap — updated 2026-11-24

Published baseline before this increment: GitHub `main` at `9fdd77a`
(P09 current/staged TLS bundle comparison), with successful CI. The earlier integration
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
- Local server certificate inventory, fingerprint/validity diagnostics and advisory
  30-day renewal warning in `sync check`, with strict expired/future denial (ADR-028).
- Read-only current/staged TLS bundle comparison with certificate/trust fingerprint
  deltas, stable node settings and explicit operator recovery checks (ADR-029).
- Independent candidate TLS password sources for comparison, strict failure without
  fallback and explicit runtime password selection for activation/recovery (ADR-029).
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
- Ordinary P07 lifecycle audit retains complete snapshots, actor, purpose and
  deprecation reason atomically; signed P07 retains its existing facts without
  unsigned duplicates (ADR-025).
- Explicit Knowledge revision creates a new candidate with a retained predecessor,
  immutable version content/links, exact reason and source, local/signed audit, and
  stale/fork/lineage checks (ADR-026). Validation remains a separate operation.
- P05 Decisions retain exact Mission terms; execution requires current ACTIVE terms
  to match. Action history includes the snapshot, while migration retains unbound
  old Decisions and historical Result reporting (ADR-027).
- JDK 21 CI with module graph checks, Detekt, tests and CLI smoke scenarios.
- Two frozen historical declared-schema profiles with synthetic governance/memory/
  credential rows, exact preservation checks, current service behavior, schema
  contracts and failed-opening retry coverage ([migration acceptance](persistence-migrations.md)).
- One captured historical runtime profile with 50 startup triggers, signed verification
  receipts, old-key approvals/revocation and key/peer lifecycle; six additional
  migration, restart, admission and transactional retry tests.
- A populated historical P10 profile with completed signed exit, approved archive,
  typed terms, retained/fulfilled obligations, pending unsigned exit and unrelated
  Core; seven migration, resumption and rollback/retry tests.
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
| 2 — in progress | Remaining command authorization and source conformance | Command audit, persisted P08 admission, authenticated delegated Core invitation/acceptance, active persisted P05 Missions, ordinary P07 source/state checks, P05 mode propagation, local P07 lifecycle audit, explicit Knowledge revision and Decision/Mission snapshot binding implemented under ADR-020/021/022/023/024/025/026/027. Remaining: initial collective mandate/root eligibility, broader representation, P05 participation/numeric revision/mode-change governance and P07 validation mandates/general branch-conflict governance |
| 3 — in progress | P09 operational lifecycle | Certificate diagnostics, bundle comparison and independent candidate passwords implemented under ADR-028/029. Next: reproducible failed TLS replacement and rollback rehearsal with journal continuity. Governed renewal/configuration changes and automated historical attestations still require their respective policies |
| 4 | Extended P10 relations and settlement | Multiparty consent and additional settlement rules from source requirements, retained obligations, failure/restart coverage, and explicit collective scope |
| 5 — in progress | Broader persistence and release acceptance | Four pinned profiles and 20 migration tests implemented, including signed key/receipt/peer and populated P10 history. Remaining: additional historical states, versioned release migrations, further failure points, missing conformance tests, public API/GUI scope and release evidence |

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
followed by P05 participation/numeric revision/mode-change governance and P07 validation mandates. ADR-021 records
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
Mission.mode, and new signed Action facts bind it. ADR-027 now requires the current
Mission to match the terms saved with the Decision, including mode. No public
mode-change command or protection-specific policy is added.

Ordinary P07 lifecycle audit and deprecation-reason retention are implemented under
ADR-025, with atomic local Events and restart/rollback tests. Existing history is
not backfilled; local Events do not become signed P09 facts.

P07 Knowledge version lineage is implemented under ADR-026, mapped to Memory
Architecture §§13–14/18–19. A new version is a separate candidate; its predecessor
retains content and status. The local single-successor rule rejects stale/forked
submissions without erasing stored versions. General contradiction records,
branch reconciliation and canonical-head/validation mandates remain open.

Decision/Mission snapshot binding is implemented under ADR-027, with nine new
SQLite tests. Changed terms require a fresh Decision; historical unbound Decisions
remain readable and cannot execute. This is equality of modeled content, not a
monotonic revision counter; restoring identical ACTIVE terms matches again.

Historical SQLite acceptance now includes two frozen declared-schema profiles with
SHA-256 pins and seven tests, plus a captured runtime profile and six further tests. Current store opening preserves their original rows
and supports existing governance/memory services after upgrade and restart. The
catalog deliberately does not derive old schemas or seed encodings from today's code.

Historical runtime guards and bounded signed verification/provisioning histories are
now captured from an isolated old build and tested with current services.
Populated P10 history now has a separate runtime profile with seven preservation,
resumption, signed-history and failure/retry checks. Broader opening-failure coverage
and versioned release migrations remain separate work.
P09 certificate-expiry diagnostics now report all configured server leaves with
fingerprints, exact validity and an advisory 30-day window; invalid dates still fail.
Current/staged TLS bundle comparison now reports leaf/trust deltas and checks previous
material locally; the runbook requires revalidation and authenticated exchange during recovery.
Independent candidate password sources now support staged password changes without
implicit fallback. Next functional increment: rehearse failed TLS replacement and
rollback with the previous certificates, trust and password, verifying journal continuity
and authenticated exchange. Issuer selection and automatic issuance remain separate decisions;
local comparison does not establish remote acceptance.
Keep P05 participation and collective mandate decisions explicit; do not infer a
quorum or Mission ownership authority from the current model. P09 operational
lifecycle and extended P10 settlement remain the subsequent functional tracks.
