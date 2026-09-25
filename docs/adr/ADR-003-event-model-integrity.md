# ADR-003 — Event Model & Integrity

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Decision

## Context

Architecture Core CORE-16 (Event as Fundamental Fact) and Data Model §31
require EVENT to carry WHO/WHAT/WHEN/WHERE/WHY/AUTHORITY/CONTEXT/RESULT
and to be immutable after fixation (Data Model §54, Memory Architecture
M-01/M-10). Security/Trust Architecture §19 requires an "integrity proof"
per critical event.

## Decision

1. **Immutability**: EVENT rows are append-only. No implementation path
   exists to UPDATE or DELETE a committed EVENT row. Corrections are
   modeled as new EVENT rows with a `corrects_event_id` reference —
   both remain readable (this directly implements Memory Architecture
   §18 "old knowledge is not silently deleted").
2. **Integrity proof**: each EVENT is content-hashed (SHA-256 over a
   canonical serialization of its fields) at creation time. The hash is
   stored alongside the event. Actor-level signing (asymmetric
   signature over the hash, tied to the actor's IDENTITY credential) is
   REQUIRED for R2+ risk actions (per Agent/AI Architecture §24 Risk
   Model, reused here for human actions of material consequence) and
   OPTIONAL for R0/R1 in MVP.
   - Concrete signature algorithm (Ed25519 vs. other) is deferred to a
     follow-up ADR-003a before Phase 3 credential work begins — this is
     a cryptographic library choice, not resolved here.
3. **Minimal EVENT schema** (MVP):
   ```
   event_id, type, actor_id, timestamp,
   context, authority_ref, decision_ref,
   payload_json, result_ref, provenance_json,
   content_hash, signature (nullable in MVP for R0/R1),
   corrects_event_id (nullable)
   ```

## Rationale

Directly implements Data Model §54 "Immutable history" and Security
Architecture §19 "Immutable Accountability" without prematurely fixing a
specific crypto stack (Protocol Architecture §31 explicitly defers
cryptographic implementation).

## Consequences

- Every write path in the Application layer MUST go through a single
  `EventStore.append()` port — no direct table mutation of derived state
  is permitted to bypass event creation (this is enforced by an
  architecture invariant test, see `tests/architecture_invariants`).
- Derived/current STATE tables (Data Model §40) are projections computed
  from the EVENT log and MAY be rebuilt from it.

## Reversibility

Hash algorithm and signature scheme are replaceable (tracked separately).
The append-only + provenance-chain principle itself is NOT replaceable
without violating Architecture Core CORE-16 and Memory Architecture M-01.
