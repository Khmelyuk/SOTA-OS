# ADR-025 — Atomic local P07 lifecycle audit

Date: 2026-11-21
Status: Accepted for the bounded local MVP slice

## Source and reproduced gap

Memory & Knowledge Architecture v0.1 §13 (PDF p.9) requires attribution and
reasons for knowledge changes; §27 (pp.16–17) addresses integrity of history;
§34 (pp.20–21) distinguishes creation, validation and withdrawal governance;
§43 M-10 (p.26) requires retaining the history of knowledge development.
Protocol Architecture v0.1 §10 (p.10) separates facts, Experience, Knowledge and
canonical Knowledge. These are paraphrases, not a full versioning or collective
approval protocol.

SHA-256 fingerprints:

- Memory & Knowledge Architecture: `1818e8fd7265ac3c8f222d9df39d479fb03506729f081a61e9ba3cb9fcabe079`.
- Protocol Architecture: `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.

ADR-023 added exact sources, current states and transactions. Ordinary P07 still
changed entities without recording lifecycle Events; deprecation required a reason
but discarded it. Four of five new LocalMemoryAuditTest regressions failed against
that baseline. Signed P07 already retained signed snapshots and reasons under ADR-019.

## Decision

MemoryService records each successful mutation through a typed MemoryAudit port.
The default LocalMemoryAudit uses the same EventStore, clock and ID generator as
the service. It writes one unsigned append-only Event:

| Operation | Local Event |
|---|---|
| createExperience | LOCAL_EXPERIENCE_RECORDED |
| proposeKnowledgeCandidate | LOCAL_KNOWLEDGE_PROPOSED |
| validateKnowledge | LOCAL_KNOWLEDGE_VALIDATED |
| deprecate | LOCAL_KNOWLEDGE_DEPRECATED |

Payload format `auditVersion = 1` includes entityId, invocation purpose, exact
reason (nullable except deprecation), full `after` snapshot, full `before` Knowledge
snapshot for transitions, and full source Experience for a proposal. Snapshots
include all domain fields, context and original provenance. Creation has no before
snapshot. Event actor/provenance identify the actual invoking subject, including a
validator different from the original author; entity provenance remains unchanged.
Event time is the injected clock's audit time.

Experience audit links its source Events through provenance. Knowledge audit
retains its source Experience IDs and snapshots; it does not invent a previous
audit Event for legacy entities. These local links are not P09 causal parents.

Source/state/actor/rights checks and ID collision guards run before audit. Audit is
appended before the entity write inside the ADR-023 shared repository transaction.
Failure at either write rolls back both. Hosts sharing the SQLite connection must
hold its local-access gate, and all injected repositories/recorders must share the
transaction. Custom MemoryAudit implementations are trusted adapters and must honor
this contract; the standard compositions provide no audit-disable switch.

The local content hash uses SHA-256 of length-prefixed typed values with sorted map
keys, including Event identity, actor, time, context, payload and provenance. It is a
local diagnostic fingerprint, not a signature or the P09 canonical integrity profile.
Event table update/delete triggers and duplicate-ID rejection protect local history.

## Signed composition

SignedP07Runtime injects SignedMemoryAudit instead of the local recorder. It retains
preflight source verification before service policy evaluation, then rechecks the
source immediately before recording the existing signed fact. SignedMemoryJournal
still writes its existing snapshot/purpose/reason payload, causal parents, canonical
signature and verification receipt. No LOCAL_* duplicate is created and the signed
wire format is unchanged. Audit and entity writes share its outer store transaction.

## Compatibility and limits

No migration or backfill is required. Existing entities can acquire their first
local transition Event without fabricating earlier creation/validation history.
Reasons are retained in immutable Event payloads, not newly populated into the
legacy optional metadata columns of the current Knowledge row. EventStore readers
can filter by LOCAL_* type and payload.entityId; there is no new audit CLI or index.

Local audit remains unsigned and outside the P09 journal/receipts. It does not
upgrade ordinary history to signed evidence or authorize remote business replay.
Existing strict export/signature boundaries remain. The generic recordEvent entry
point is still unsupported. No Person/Core/Sota mandate or authentication proof is
inferred from a recorded actor.

Knowledge version numbers and supersedes links are preserved, not advanced by
lifecycle changes. [ADR-026](ADR-026-knowledge-version-lineage.md) subsequently adds
explicit new-version creation and checks the known lineage. General branch/conflict
governance, canonicalization mandates and historical backfill remain separate work;
this is bounded M-10 evidence, not completion of all Memory governance requirements.

## Evidence

LocalMemoryAuditTest covers restart with full transition snapshots and exact reason,
validator/author separation, append-only history, event/entity failure at every
operation with retry after reopen, denial/stale-input silence, ID collision and
legacy transitions. SignedMemoryTest checks that entity history has exactly one
signed Event per fact, while existing signed atomicity/recovery tests retain their
full rollback and two-node coverage.
