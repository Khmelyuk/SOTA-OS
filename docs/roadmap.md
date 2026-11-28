# Development roadmap — updated 2026-11-28

Published baseline before this increment: GitHub `main` at `736ceb82`
(owner's Genesis proposal and roadmap link), with successful
[CI](https://github.com/Khmelyuk/SOTA-OS/actions/runs/46145143862). The earlier integration
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
- A [real CLI TLS rollback rehearsal](p09-tls-rollback.md): candidate trust failures
  in both directions preserve journals/checkpoints; previous material restores eight
  identical signed records and authenticated exchange. Included in the test suite.
- A [staged TLS rollout rehearsal](p09-tls-rollout.md): overlap trust supports old/old,
  new/old and new/new peers; retired certificates are rejected in both directions.
  Fresh signed records at each stage converge to 24 matching records and checkpoints.
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
- Retained [build-evidence bundles](release-evidence.md) after successful CI acceptance:
  exact commit/tree/run identity, CLI ZIP/TAR digests, JUnit reports and fixture hashes.
  Eleven collector tests check refusal of incomplete/changed inputs. Artifacts expire
  after 30 days; named release and upgrade certification remain separate.
- Four [stopped-node recovery cases](node-backup-recovery.md), covering signed P09
  history and pending P10 continuation under DELETE/WAL. Exact backup/restore rows,
  catalog, receipts, signature checks and retained obligations supplement the
  historical upgrade tests; this does not certify live backup or remote freshness.
- Two frozen historical declared-schema profiles with synthetic governance/memory/
  credential rows, exact preservation checks, current service behavior, schema
  contracts and failed-opening retry coverage ([migration acceptance](persistence-migrations.md)).
- One captured historical runtime profile with 50 startup triggers, signed verification
  receipts, old-key approvals/revocation and key/peer lifecycle; six additional
  migration, restart, admission and transactional retry tests.
- A populated historical P10 profile with completed signed exit, approved archive,
  typed terms, retained/fulfilled obligations, pending unsigned exit and unrelated
  Core; seven migration, resumption and rollback/retry tests.
- An explicit [upgrade matrix](release-upgrade-matrix.md) for four commit/profile
  sources, with preservation/failure/retry/restore criteria and the focused 49-test
  `:test:migrationAcceptance` task. Twelve cases verify recovery after completed
  Decision DDL, credential-baseline statements and before the version marker.
- Persisted SQLite baseline `1` and shared fresh/legacy store opening under ADR-030.
  Unknown version numbers fail before schema writes; four historical-profile tests
  exercise CLI upgrade and unchanged-byte rejection, and seven API tests cover fresh
  creation and invalid opening paths. The opening inventory is documented.
- A [canonical baseline catalog](sqlite-schema-inventory.md): 38 tables, two explicit
  indexes and 51 triggers, with the exact 41-trigger generated/runtime delta. Seven
  tests pin fresh DDL and historical index/guard definitions, including six reviewed
  text variants. Production SQL and historical resources are unchanged.
- Frozen empty SQLDelight `1.db`, mandatory checksum verification and substantive
  migration comparison in `check`. Two snapshot tests prove empty declared state and
  runtime completion with preserved seeded data. An isolated canary rejects column/FK
  drift and accepts a matching numbered migration. A build-only migrations 2.3.2 pin
  fixes comparator traversal while application drivers/compiler remain 2.0.2.
- Transactional numbered migration dispatch under [ADR-031](adr/ADR-031-numbered-sqlite-migrations.md):
  ordered generated/runtime steps, target/FK validation, atomic guards and marker,
  fresh creation and preserved legacy adoption. Thirty-three persistence tests cover
  rollback, commit contention and DELETE/WAL child-process recovery. Production stays
  at version `1`; synthetic higher-version tests are not release certification.
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
| 3 — in progress | P09 operational lifecycle | Certificate diagnostics, bundle comparison, independent candidate passwords, rollback and overlapping-trust rollout rehearsals implemented under ADR-028/029. Public deployment, governed renewal/configuration changes and automated historical attestations remain open and require their respective policies |
| 4 | Extended P10 relations and settlement | Multiparty consent and additional settlement rules from source requirements, retained obligations, failure/restart coverage, and explicit collective scope |
| 5 — in progress | Broader persistence and release acceptance | Four historical profiles, 49 migration/schema/restore tests, canonical catalog and frozen SQLDelight verifier baseline implemented. Numbered dispatcher and 33 transaction/crash-recovery tests are implemented. CI evidence bundles bind commits to distributions, reports and Genesis review-input hashes. Next: approve a domain change before certifying its numbered migration with populated profiles and named release identities. Named release support, further historical states, conformance tests and public API/GUI scope remain open |

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
existing Core or its quorum. A [Core Genesis Mandate proposal](core-genesis-mandate-proposal.md) now records
unanimous founding consent, a proposed two-thirds renewal threshold and a candidate
schema 2 feature. Existing-Core root eligibility and final policy confirmation remain
open; no voting rule is implemented. [ADR-032](adr/ADR-032-core-genesis-mandate.md)
now provides a **Proposed** decision, pinned constitutional/axiom trace and model
compatibility review, 24 planned acceptance scenarios, migration/recovery/CI gates
and [Q01–Q09 for separate discussion](core-genesis-mandate-questions.md).
The Constitution source is itself an unadopted draft; neither its text nor schema 1
is changed. Resolve subject recognition, collective/holder mapping, original capacity,
current-Core electorate/cutover, withdrawal scope and offline freshness before the
corresponding implementation. Independent node/P10 recovery proceeds above; multiparty
settlement still needs its own policy. Independent P05 Mission existence/state work is
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
resumption, signed-history and failure/retry checks. Twelve additional opening cases
cover completed Decision DDL, credential-baseline statements and the completion marker
in every profile, then compare retry/reopen with an uninterrupted upgrade. ADR-030
adds version recognition and a minimum core-table preflight; complete malformed-schema
validation, storage failures and actual versioned release migrations remain separate work.
ADR-031 separately covers abrupt process termination for numbered upgrade transactions.
P09 certificate-expiry diagnostics now report all configured server leaves with
fingerprints, exact validity and an advisory 30-day window; invalid dates still fail.
Current/staged TLS bundle comparison now reports leaf/trust deltas and checks previous
material locally; the runbook requires revalidation and authenticated exchange during recovery.
Independent candidate password sources now support staged password changes without
implicit fallback. Failed TLS replacement and previous-bundle recovery now have a
real CLI rehearsal with journal continuity and authenticated exchange checks.
Overlapping-trust rollout and retirement now have a real CLI rehearsal with fresh
signed records at each stage and explicit old-certificate rejection after retirement.
The upgrade matrix now identifies the four verified sources and failure/retry criteria.
The persisted baseline and opening inventory are now implemented under ADR-030.
The exact generated/runtime delta is now pinned by canonical-catalog acceptance.
SQLDelight verification now has a frozen input, tested positive/negative canary and
documented ordering contract. Numbered dispatch, transaction/retry and guard-before-marker
behavior are now implemented. Next independent increment: define release identities and
acceptance criteria for the first real domain-motivated schema change. Direct `.sq` placement of
conditional guards failed in the configured generator; retain their executable SQL.
There are
no published release/tag identities yet; `0.1.0-mvp` alone cannot identify a schema.
Issuer selection and automatic issuance remain separate decisions;
local comparison does not establish remote acceptance.
Keep P05 participation and collective mandate decisions explicit; do not infer a
quorum or Mission ownership authority from the current model. P09 operational
lifecycle and extended P10 settlement remain the subsequent functional tracks.
