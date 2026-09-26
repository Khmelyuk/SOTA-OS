# ADR-010 — Durable voluntary exit from a Core

Status: ACCEPTED for local voluntary self-exit
Updated: 2026-09-26

## Source

MVP Specification v0.1 §16, PDF page 18: AC-18 “Користувач може вийти з Core”.
Protocol Architecture v0.1 §13, PDF page 13 requires request, authority verification,
delegation revocation, relation closure, obligation settlement, allowed export,
then termination. Source fingerprints: [criteria mapping](../ac-p09-mapping.md).

## Decision

Replace the in-memory stub with a typed `(Person, Core)` target and SQLite-backed
exit repositories. The authenticated Person may exit their own active membership
without a central service or independently granted exit permission. Every stage
rechecks actor identity and RightsConstraint. Another Person and Agents cannot
force this flow.

Stages: REQUESTED → DELEGATIONS_REVOKED → RELATIONS_CLOSED →
OBLIGATIONS_SETTLED → EXPORT_READY → COMPLETED. Each transition, domain mutation,
stage audit and P06 event commit in one SQLite transaction. Earlier successful
stages remain committed if a later stage fails. One open exit per Person/Core
allows retries after restart without duplicate transition events. This is not
one transaction spanning file delivery.

Revoke nonterminal authorities accountable to the selected Core whose direct
subject or issuer is the leaving Person. Close scoped TRUST/AGREEMENT inventory
entries. Other Core memberships, authorities and inventory survive. Pending-exit
SQLite triggers prevent adding/reactivating relevant commitments before completion.

Unfulfilled obligations become RETAINED with the Person's declaration: responsibility
survives exit. Retention does not assert payment, performance, transfer, discharge,
or third-party agreement. FULFILLED requires a separate human operator, independent
active Core-accountable authority with explicit operation/resource scope, matching
context, RightsConstraint, and a nonblank evidence reference. This local MVP policy
permits exit without erasing unresolved responsibilities. It does not implement a
general settlement engine or decide whether contractual restrictions are lawful.

## Export and completion

Own identities are portable automatically; credentials and signing keys are excluded.
Event, knowledge and evidence bodies require a separately authorized local export
grant. The grant freezes the reviewed JSON snapshot. Authorship alone does not
authorize copying shared payloads. Events require the Person as actor and an
authority accountable to the target Core. Knowledge requires matching author
and nonempty derivation through owned, Core-scoped events. Evidence ownership and
Core are explicitly recorded; source events, when present, must match that scope.
Unknown provenance fails closed. Grants and fulfillment record operator/authority audit.

The versioned JSON archive and SHA-256 are durable and append-only. Later knowledge
edits do not change approved content. The archive also records retained obligations.
`P10Runtime.leave` writes a new owner-only file, flushes it and its parent directory,
then passes its digest to termination. Existing paths are never overwritten.
Failed delivery leaves membership active and the archive available; resume with a
new output path. The low-level service trusts the local host's digest acknowledgement;
it cannot prove a human retained the file.

Final termination rechecks absence of scoped live delegations, relations and OPEN
obligations and revokes only the selected active membership. Person, identity,
credentials, other Core memberships and immutable history remain intact.

## Boundaries

- Hosts must authenticate invocations. The CLI uses provider-backed authentication
  and exact typed confirmation before requesting exit.
- Inventory insertion is a trusted local adapter operation. General Trust/Agreement
  persistence, contract ingestion and arbitrary downstream delegation lineage are
  not implemented; direct subject/issuer revocation is the supported boundary.
- P10 events are local unsigned R0 records. They cannot pass strict signed P09
  admission until a signed producer is integrated; immutable events are not rewritten.
- Third-party expulsion, cancellation/reinstatement of an in-progress exit, remote
  P10 dispatch and a general legal settlement engine remain outside this slice.
- Export file delivery targets POSIX filesystems (the Linux CLI host).
- Existing P08 membership-only leave remains separate; callers needing coordinated
  cleanup must use P10.
