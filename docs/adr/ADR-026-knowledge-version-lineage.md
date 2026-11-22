# ADR-026 — Immutable Knowledge versions and explicit revision

Date: 2026-11-22
Status: Accepted for the bounded local MVP slice

## Source and reproduced gap

Memory & Knowledge Architecture v0.1 §13 (PDF p.9) requires version history with
content, actor, reason, evidence, time, confirmation and resulting experience.
§14 (pp.9–10) connects source Events, Experience and Knowledge. §18 (p.12) retains
obsolete knowledge; §19 (pp.12–13) preserves contradictory sources and contexts
instead of automatically erasing disagreement. Protocol Architecture v0.1 §10
(p.10) distinguishes proposed Knowledge from confirmed or canonical Knowledge.
These are paraphrases; they do not specify a complete branching/approval protocol.

SHA-256 fingerprints:

- Memory & Knowledge Architecture: `1818e8fd7265ac3c8f222d9df39d479fb03506729f081a61e9ba3cb9fcabe079`.
- Protocol Architecture: `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.

Knowledge already stored `version` and `supersedes`, but no command created a
successor. The repository could overwrite content or accept missing predecessors,
invalid numbering and duplicate successors. Three of four KnowledgeLineageTest
regressions failed against the preceding implementation; the legacy-root case passed.

## Command contract

`MemoryKnowledgeProtocol.reviseKnowledge(invocation, previous, from, statement, reason)`
creates a new ID with status CANDIDATE, version `previous.version + 1`, and
`supersedes = previous.id`. Ordinary MemoryService and SignedP07Runtime implement it.

- Both sources must exactly equal their stored records and share the invoking
  author's identity and context. Their provenance times cannot be in the future.
- The predecessor must be CANDIDATE, VALIDATED or DEPRECATED, with valid recorded
  ancestry and room for the next positive Int version. Other states remain outside
  this bounded lifecycle.
- Statement must be nonblank and changed; reason must be nonblank and is retained
  exactly. RightsConstraint receives the persisted context and `reviseKnowledge`.
- The new record derives from the supplied Experience; its provenance records the
  invoking author, current injected time, source Event, predecessor and Experience
  IDs. Its evidence list starts empty; previous evidence remains in the predecessor.
- A predecessor may have only one new local successor. Repeating or forking a
  revision raises an error before audit. Generated ID reuse also fails before audit.

Creating a candidate does not change the predecessor's text, provenance, evidence
or status, including VALIDATED status. In particular, `supersedes` is the declared
revision link, not automatic approval or a transition to SUPERSEDED. Validation of
the new candidate is a separate operation with its own recorded validator. Later
explicit lifecycle operations can still change either version's status.

The single-successor rule is a bounded implementation choice, not a claim that the
source mandates a global linear history. Competing submissions receive an explicit
failure; no existing version is overwritten and no last-write-wins resolution is
performed. General contradiction records, branches, branch reconciliation, canonical
head selection and collective validation mandates remain separate work.

## Persistence and audit

KnowledgeRepository exposes `findSuccessors`. Each SQLite save checks immutability
and lineage inside its own transaction, including direct repository callers. An
existing record permits a status-only change; its SQL UPDATE touches only status.
New records require positive versions and a complete known ancestor chain with
matching context/author, immediate version increments and no cycles or missing
parents. The new predecessor must have no existing successor. SQLite serializes
writes; a stale concurrent writer must fail rather than replace the committed child.
Injected repositories must enforce the same contract and share the service transaction.

Trusted legacy roots may retain positive version numbers above one with no recorded
predecessor. No unavailable ancestors are invented. Existing rows and branches are
not migrated, rewritten or silently consolidated. This adds queries over existing
columns, without schema changes or backfill. Raw SQL remains a trusted storage
boundary; this increment does not add Knowledge table immutability triggers.

Ordinary revision appends LOCAL_KNOWLEDGE_REVISED with the complete predecessor,
new candidate and source Experience snapshots, exact reason, actor, time and purpose.
Audit and new entity commit together. Existing local snapshot format remains version 1.

Signed revision requires exact verified local snapshots for both sources, then emits
KNOWLEDGE_REVISED with both source fact IDs as causal parents. The existing full JSON
snapshot includes version/supersedes; the canonical signature binds snapshot, reason,
context and both parent links. Current-key checks, verified receipts and the outer
store transaction remain mandatory. Revised candidates can be validated, deprecated
or revised again after reopen. No unsigned duplicate is emitted.

P09 receives these facts without creating remote Knowledge entities or approving
them. Ordinary legacy sources do not acquire signed history. No public revision CLI,
remote command dispatcher, implicit collective mandate or historical backfill is added.

## Evidence

KnowledgeLineageTest covers repository immutability, invalid chains, duplicate
successors, restart and legacy roots. KnowledgeRevisionTest exercises ordinary and
signed compositions: retained approved predecessor, three versions across reopen,
exact reason/provenance/source links, stale/forged input, actor/context denials,
ID collisions, rights context, and rollback/retry for entity/Event/journal/receipt
failures. KnowledgeRevisionBoundaryTest covers lifecycle limits, corrupt ancestry,
overflow and signed proof for both sources. SignedMemoryTest exchanges seven facts,
including revision and its validation, after lost acknowledgement and reopen without
remote entity materialization. Existing corruption tests explicitly bypass the
repository through SQL so they continue testing export and signed-history integrity.
