# ADR-008 — Scope Authority for Membership Removal

- **Status:** Accepted for the current MVP slice
- **Date:** 2026-09-24
- **Scope:** P08 membership removal

## Context

P08 `remove()` required an Authority identifier but previously ignored it. A
non-null identifier alone does not prove that the caller may remove a member.
Authority is delegated, scoped, time-bounded, revocable, and accountable. The
shared `Scope.resources` field also needs consistent coverage semantics.

## Decision

A membership-removal Authority must satisfy all of the following before the
Rights/Dignity constraint is evaluated:

1. The Authority exists, is ACTIVE, and is valid at the current instant.
2. Its subject is the authenticated invocation actor.
3. Its `accountabilityTarget` is the collective whose membership is removed.
4. Its action scope contains `membership.remove`.
5. Its resource scope contains the exact target `core:<id>` or `sota:<id>`.

An empty `Scope.resources` set means unrestricted resources. It may cover a
resource-specific request, but a resource-limited Authority cannot cover an
unrestricted request. Delegation applies the same scope coverage rule, so a
restricted issuer cannot delegate unrestricted resource access.

After these checks pass, P08 still evaluates the Rights/Dignity constraint;
Authority does not override that constraint.

## Consequences

- Callers must grant membership-removal authority with explicit action and
  collective resource scope.
- Broad legacy grants with no resource list remain unrestricted by design; a
  future migration may choose to narrow them.
- This decision closes the identifier-only check in `remove()`. Other P08
  commands still require their command-specific authorization rules.
