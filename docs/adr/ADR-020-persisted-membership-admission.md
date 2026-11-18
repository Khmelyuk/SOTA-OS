# ADR-020 — Persisted membership admission and transitions

Date: 2026-11-18
Status: Accepted for the bounded local P08 slice

## Context and source boundary

CORE-12 voluntary association and the existing MembershipProtocol distinguish an
invitation from acceptance. ADR-008 requires explicit collective-scoped authority
for removal; ADR-013 requires checking its recorded lineage. Those checks did not
prove that a caller-supplied Membership existed or still had its supplied state.
`invite` returned an unrecorded value and `accept` could persist any matching
Person's fabricated value. Replay could also restore a revoked membership.

The following is an explicit local implementation decision. It does not invent
a complete collective mandate, voting or delegated invitation protocol from
those principles. The [command audit](../command-authorization.md) tracks that gap.

## Decision

- Hosts authenticate every invocation, including representation of a Core/Sota.
  In this slice only the target collective can issue an invitation. A Person,
  Agent or another collective cannot do so merely by naming the target. There is
  no implicit invitation authority from membership, founder status or a role.
- `invite` persists PROPOSED membership with unused identity terms before returning.
  Membership's existing identity terms are Person, collective, optional role and
  start instant. No new ID field or schema migration is introduced.
- `accept` requires the invited Person and the exact persisted PROPOSED record;
  `leave` requires the member and the exact persisted ACTIVE record. `remove`
  requires that same ACTIVE record plus the existing ADR-008/013 authority checks.
  All transitions require no end time and a clock at or after the persisted start.
- Every operation checks rights before writing. Lookup, authorization and mutation
  run in the existing AuthorityRepository transaction; all SQLite repositories
  supplied to the service must share the same store. Hosts sharing its connection
  must use the store's local-access gate. Core creation and founding membership
  also commit or roll back together.
- Lookup uses identity terms, compares the complete stored object and updates its
  actual storage ID. Existing rows with legacy storage IDs remain usable. Ambiguous
  duplicate terms fail closed; no row is chosen arbitrarily. Ordinary repository
  `save` remains a trusted storage adapter, not an authenticated command endpoint.

## Compatibility and limits

Previously unrecorded invitation objects cannot be accepted: the collective must
issue a new persisted invitation. Repeating accept, leave or remove with an old
snapshot is rejected, including after restart. Reinvitation needs new identity
terms (normally a later start instant); it cannot overwrite terminal history.
Multiple distinct role memberships retain the existing model's semantics.

Founding a new Core still requires no central administrator. Its explicit founder
operation creates membership directly and grants no ownership or Authority.
P08 leave/remove remain membership-only operations; they do not replace durable
P10 export, delegation cleanup or obligation settlement. Signed P08 facts,
invitation cancellation, delegated invitations, collective identity verification
and complete admission policy remain separate work. Existing stored memberships
are trusted local data; this change does not reconstruct their historical approval.

## Evidence

MembershipLifecycleTest covers fabricated/stale/altered records, issuer and
invited-Person boundaries, clock checks, role isolation and reopen/reinvitation.
MembershipAuthorizationTest covers removal authority, policy denial and atomic
founder creation. MembershipPersistenceTest covers legacy IDs, ambiguous terms,
failed transition rollback and retry after reopen. Existing Core Loop, P10 and
Research tests exercise their composition with the new P08 checks.
