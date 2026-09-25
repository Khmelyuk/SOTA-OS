# ADR-006 — AI Boundary in MVP

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Decision (constrained directly by Architecture Core
CORE-21 and Agent/AI Architecture — those ARE architectural, not merely
technological, and this ADR must not weaken them)

## Context

MVP Specification §12/§13/§23 (Phase 5) requires that the Core Loop work
*without* AI before AI is introduced, and explicitly excludes "autonomous
AI agents with broad authority" and "fully automated decision-making"
from MVP. Agent/AI Architecture §51 defines a full AGENT data model.
Architecture Core CORE-21: `AI != PRINCIPAL`.

## Decision

`AGENT` is modeled as an entity **stub** in the Domain layer from the
start of MVP, so that the Authority/Delegation Protocol (P04) already
supports "principal delegates to AGENT" as a case, without a later
schema migration. However:

- **No execution runtime** ships in MVP. No code path exists that lets
  an AGENT autonomously perform an ACTION.
- AGENT MVP fields: `agent_id, owner_principal_id, purpose, capabilities
  (declared, not executable), authority (always empty in MVP),
  constraints, status`.
- Any AGENT present in MVP data is at **Level 0 (Observe)** per Agent/AI
  Architecture §10 at most: it may be attached to read-only
  Memory/Knowledge query use-cases (e.g. "suggest an EXPERIENCE
  clustering") in a later MVP phase, and even then its output is a
  `KNOWLEDGE_CANDIDATE`, never a `CANONICAL_KNOWLEDGE` or an `ACTION`.
- Self-escalation is structurally impossible in MVP: there is no
  `Agent.delegateTo(self)` or `Agent.requestAuthority()` API — delegation
  can only be initiated from the PERSON/CORE/SOTA side (P04 flow),
  never accepted-and-escalated from the AGENT side.

## Rationale

Prevents a costly Data Model migration when AI is introduced in Phase 5
(MVP Roadmap §23), while strictly honoring MVP Spec's "prove the Core
Loop works without AI first" and Architecture Core's `AI != PRINCIPAL`
invariant — this is forward-compatibility, not scope creep, because
zero executable capability is granted.

## Consequences

- `tests/architecture_invariants` MUST include a test asserting no code
  path allows `AGENT` to call `AuthorityProtocol.grant()` on itself or
  to execute `ActionProtocol.execute()` without a human/SOTA-issued
  Authority reference (AC-16 in MVP Acceptance Criteria).

## Reversibility

The stub is intentionally inert; enabling real AGENT execution is a
Phase 5 decision requiring its own ADR and its own explicit human
authorization step, not an automatic unlock.
