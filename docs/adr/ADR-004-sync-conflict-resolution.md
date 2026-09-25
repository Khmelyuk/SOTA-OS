# ADR-004 — Sync & Conflict Resolution

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Decision

## Context

Protocol Architecture P09 requires sync to be "узгодження станів, а не
дозвіл на існування" (reconciliation, not a precondition for existence)
and explicitly defers the concrete algorithm (§31: no CRDT/blockchain
mandated at Protocol level). Data Model §56 requires event identity,
origin, timestamp, version, causality, conflict state, and sync status
to be representable. Memory Architecture §19/§36 requires that
contradictory knowledge/events are preserved as data, not silently
overwritten ("Conflict is data, not failure").

## Decision

**Append-only event log + context-aware deterministic merge. No
last-write-wins. No full CRDT in MVP.**

Sync procedure (per local node):

```
LOCAL EVENT LOG (unsynced tail)
   ↓
SYNC REQUEST (since last known remote cursor)
   ↓
REMOTE EVENT LOG (delta)
   ↓
CAUSALITY CHECK (per-entity: do the two deltas touch the same
   entity's derived state in a way that produces divergent results?)
   ↓
   ├─ NO CONFLICT → append both event sets to local log,
   │                recompute projections
   │
   └─ CONFLICT DETECTED → do NOT discard either side.
        Create a `CONFLICT` record referencing both EVENT ids,
        their provenance, and authority chains.
        Projected/derived STATE for the affected entity is marked
        `CONTESTED` until a resolution EVENT (human or SOTA-authorized)
        is recorded.
```

"Conflict" is defined narrowly for MVP: two events from different origins
that assert incompatible derived state for the *same entity* within an
overlapping validity window (e.g. two AUTHORITY grants for the same scope
with different terms). Independent events (different entities, or
additive facts like two separate EXPERIENCE records) are never conflicts
— they are simply merged.

## Rationale

Directly implements:
- CORE-16 Event as Fundamental Fact (event, not current-state, is
  authoritative)
- P-I09 Provenance and M-10 Historical Integrity (nothing is silently
  overwritten)
- Memory Architecture §19 "Conflict is data, not failure"

Avoids prematurely committing to a CRDT type system, which Protocol
Architecture explicitly reserves for a later Implementation-level choice.

## Consequences

- Every entity with mutable derived state needs a defined "what counts
  as conflicting" rule (documented per-entity in the Domain layer, not
  centrally).
- A `CONTESTED` state must exist in the generic entity lifecycle
  (see ADR baseline in Implementation Plan, State Machine section).
- Conflict resolution UI/flow is out of MVP scope beyond recording and
  exposing the CONFLICT record (resolution mechanism itself may be
  manual for MVP).

## Reversibility

Replaceable by a CRDT-based or vector-clock-based implementation later,
provided the guarantee "no event is discarded without a recorded
resolution event" is preserved.

## Implementation status — 2026-09-25

The P09 reconciliation slice now provides durable per-peer cursors,
append-only event metadata, deterministic causal merge, retained
conflicts and recomputed `CONTESTED` projections. Entity rules cover
Authority, Knowledge and additive Experience assertions. Existing local
Authority reads reflect sync conflicts, so P05 cannot execute using a
contested authority. JSON exchange and the HTTPS adapter have two-node
integration coverage. Scope, composition and remaining production
admission/hosting work are documented in [P09 sync](../p09-sync.md).
Resolution workflows and generalized domain-table replay remain outside
this slice.
