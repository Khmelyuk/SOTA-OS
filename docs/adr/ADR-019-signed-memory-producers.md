# ADR-019 — Signed P07 memory producers

Date: 2026-11-18
Status: Accepted for the bounded local MVP slice

## Context

P05 and P10 already produce canonical signed P09 facts atomically. The Core Loop's
Experience and Knowledge records had no equivalent signed history. Signing a
caller-supplied or mutable snapshot without checking persisted evidence would
allow unsigned or replaced data to acquire an unsupported provenance claim.

## Decision

Add an opt-in `SignedP07Runtime` around the existing MemoryService. Require exact
persisted sources plus canonical local signed history and verification receipts.
Carry complete versioned snapshots in four facts: EXPERIENCE_RECORDED,
KNOWLEDGE_PROPOSED, KNOWLEDGE_VALIDATED and KNOWLEDGE_DEPRECATED. Bind their causal
parents and keep transition authors distinct from original entity provenance.

Use the shared store gate and one SQLite transaction for entity, Event, journal
and receipt. New facts require current provisioned actor keys; exact historical
source evidence follows ADR-012. Keep the existing non-Agent governance and rights
checks, require local actors, reject stale transitions and Knowledge ID collisions.

## Consequences

P09 can exchange memory facts with restart/replay safety. Receiving a fact does
not replay business commands or canonicalize Knowledge. Legacy unsigned and
Research history are not implicitly promoted. No migration or new CLI is needed.
Authenticated host composition, broader collective authorization, remote entity
projection and command idempotency remain explicit separate work. Snapshot lookup
currently scans the local journal; indexing may be added when scale requires it.

The [producer contract](../p07-signed-memory.md) and SignedMemory acceptance tests
define this boundary. The two-node HTTPS rehearsal includes Experience and
Knowledge proposal facts without changing P09 admission or merge semantics.
