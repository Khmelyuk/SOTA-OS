# Development roadmap — updated 2026-11-18

Baseline: GitHub `main` at `1f6d175` (human-invoked Research CLI). The local
checkout had been based on `ece3ad1` and was missing 26 upstream commits.
The previous local plan is superseded by this review of the integrated code.

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
| 2 — in progress | Remaining command authorization and source conformance | Command audit and persisted P08 admission implemented under ADR-020. Remaining: authenticated collective mandates/root eligibility, delegated invitations and P05/P07 conformance; no acceptance claim based only on a caller-supplied SubjectRef |
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
review is collective representation/root standing, followed by P05 mission
admission and P07 validation mandates against the source specifications.
