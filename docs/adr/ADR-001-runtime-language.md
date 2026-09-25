# ADR-001 — Runtime & Language

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Decision (NOT Architecture Core)

## Context

Architecture Core v0.1 explicitly does not define a programming language,
runtime, or platform. It requires only that the implementation preserve
architectural invariants (see `docs/architecture/01_Architecture_Core.md`).
A choice is nevertheless required to begin Phase 2/3.

## Decision

- Language: **Kotlin**
- Runtime: **JVM** for the reference server-side node implementation
- Forward-compatibility: domain and application layers written with
  **Kotlin Multiplatform (KMP)** boundaries in mind, so that local-first
  clients (mobile/desktop) can later share domain logic without a rewrite.

## Rationale

- Strong typing supports encoding architectural invariants (e.g.
  `ROLE != AUTHORITY`) as compile-time-checked value types rather than
  loose maps/strings.
- Sealed classes / algebraic data types map naturally onto the Data Model's
  Entity / Relation / State / Event / Constraint classification (Data Model
  §3).
- KMP keeps the door open for genuinely local-first clients (CORE-14 Local
  Autonomy) without committing to a specific client platform now.

## Consequences

- Domain and Application layers MUST NOT depend on JVM-only or
  Android/Server-only APIs (this is enforced by module boundaries, see
  repository structure).
- Infrastructure layer (persistence, sync transport, API) MAY use
  JVM-specific libraries.

## Reversibility

Replaceable. Per Architecture Core §31 (Technology Independence,
"Protocol over Platform"), this choice affects Implementation only and
must never be treated as an architectural constraint.
