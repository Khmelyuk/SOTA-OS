# ADR-002 — Local Persistence

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Decision

## Context

Reference Architecture §27/§31 and Architecture Core CORE-14 require a
local, autonomous node that does not depend on a central database or
service for critical operation. Data Model §55 (Local-first) requires
CORE/SOTA to store subjects, membership, missions, events, results,
knowledge, evidence, and local authorities locally.

The Data Model is graph-shaped by nature (Data Model §47, Autonomous
graph), but this does NOT require a native graph database — what matters
architecturally is: local-first, transactional, immutable events,
provenance, deterministic sync (per the discussion that produced this
ADR).

## Decision

**SQLite**, embedded, one database file per local node, as the MVP
persistence engine.

- Relations (TRUST, AUTHORITY, MEMBERSHIP, DELEGATION, RELATION,
  AGREEMENT) modeled as edge tables with `subject`, `target`, `context`,
  `scope`, `validity` columns — not as a native graph store.
- EVENT table is append-only (no UPDATE, no DELETE on committed rows;
  see ADR-003).
- Graph-style traversal queries (e.g. "trust path A→B in context C") are
  implemented in the Domain/Application layer over SQLite reads, not
  pushed into a graph query language. If traversal performance becomes a
  bottleneck post-MVP, this ADR can be superseded — see Reversibility.

## Rationale

- Zero external service dependency → satisfies Local Autonomy without
  operational overhead.
- Mature, embeddable, works identically across the eventual KMP client
  targets (via SQLDelight or equivalent) — keeps ADR-001's forward path
  open.
- Sufficient for MVP Deployment Model scale (2–5 PERSON / 1 CORE / 1 SOTA
  / 2+ local nodes, per MVP Spec §19).

## Consequences

- No native graph query language (Cypher/Gremlin) in MVP.
- Provenance and versioning (Data Model §53–54) must be modeled
  explicitly as columns/child tables, not assumed from the storage
  engine.

## Reversibility

Replaceable per CORE-22 Protocol over Platform. A future ADR may
introduce a graph store or CRDT-native store for the Network/Federation
layer (P11) without changing the Domain layer contracts, provided the
Application layer's persistence port (`EventStore`, `EntityRepository`)
interfaces are respected.
