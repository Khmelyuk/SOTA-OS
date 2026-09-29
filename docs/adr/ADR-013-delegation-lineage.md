# ADR-013 — Explicit authority lineage and cascading revocation

Status: IMPLEMENTED for recorded local authority chains
Date: 2026-09-29

## Sources and problem

Protocol Architecture v0.1 §7 P04, PDF page 7, requires traceable delegation,
limited scope, validity, revocation and accountability. Security & Trust Architecture
v0.1 §22 (PDF page 14), §23 and §29 prohibit hidden escalation through A → B → C
and require explicit source/target/authority/scope/constraints/validity/revocation/
accountability. P10 §13 requires revoking active delegations during exit.

Previously the grant service checked only whether an issuer held some covering
scope. It discarded which authority justified the grant. P10 could revoke direct
incoming/outgoing grants but could not reliably identify their descendants.

## Grant and use

Authority now has optional parentAuthorityId. New Person/Agent-issued grants must
resolve a valid parent owned by the issuer. Callers may supply a parent explicitly;
when omitted, exactly one covering candidate must exist. Ambiguity is rejected,
not resolved by storage ordering. The chosen parent is always persisted.

An originating Core/Sota invocation may create a root accountable to that same
collective. Authenticating collective invocations and establishing root governance
remain trusted-host responsibilities; this change does not implement a new
collective voting or membership-verification mechanism. Self-delegation is denied.

Each edge requires the same context/accountability target, the parent subject as
child issuer, a contained action/resource scope and a contained validity interval.
An unrestricted child resource scope cannot derive from a restricted parent. The
parent and every ancestor must currently be ACTIVE and time-valid; an expired,
future, suspended, contested, missing or cyclic ancestor denies authorization.
All new IDs must be unused.

AuthorityLineage validates iteratively and reloads stored records, so stale or
forged snapshots cannot authorize work. P04 grant/isValid, P05 decision/execution,
P08 removal, P09 provisioning and P10 export/fulfillment governance check lineage.
Extra host policy callbacks cannot bypass the mandatory P05/P08 lineage check.
P05 decision/execution and P08 removal enclose authority reads and writes in the
same repository transaction, as do P04 grants/revocations and the existing
P09/P10 governance roots. All injected SQLite repositories must share a store.
Event-write failure rolls back the P05 Action as well as the enclosing operation.

## Durable structure

SQLite adds parent_authority_id and an index for child lookup. Parent links cannot
be changed. A child must reference an existing correctly bound parent, and
connected grant terms cannot be edited in place; revoke and issue a new grant.
The application additionally checks scope/time ceilings and walks all ancestors.
Pending-exit guards reject new descendants even while the exiting ancestor's
revocation stage has not yet run.

The additive migration preserves legacy records with null parents. Null does not
prove a legacy Person grant was originally authorized: ancestry is unknown and is
not guessed from matching scopes, issuer names or timestamps. Newly issued grants
record their actual parent. This implementation cannot reconstruct unrecorded
historical chains.

## Revocation

P04 loads the stored issuer before authorizing revocation, preventing a caller
from substituting issuer fields in a supplied Authority object. Revocation needs
no network or additional approval round trip. It traverses the indexed subtree
locally, preserving parent links and terminal records.

P10 starts from every target-Core authority directly held or issued by the leaving
Person, including terminal roots whose descendants might still be live, and
revokes all nonterminal descendants. Every traversed node must remain accountable
to the same collective. Independent branches and other Core trees are untouched.
The termination check examines these full families rather than direct grants only.

Each revoked authority receives append-only audit containing the selected cascade
root, acting subject, reason and time. P10 reasons include the exit ID and declared
purpose. Cascade updates and audit share one transaction; for P10 the same
transaction includes the exit stage/event and, when enabled, signed sync journal
and signature receipt. Any failure rolls everything back. Repeating an already
completed revocation creates no duplicate audit. Traversal is iterative, avoiding
call-stack recursion and unbounded cycling on corrupt ancestry.

Revocation is local and indexed, but its work grows with the affected tree; it is
not an O(1) operation regardless of descendant count. Audit rows and grants remain
historical records. Remote P09 facts do not automatically rewrite local grant trees.

## Evidence and remaining work

- DelegationBoundsTest: scope/resources/time/context/Core ceilings, explicit and
  ambiguous parent selection, inactive ancestors, cycles, immutable links and stale data.
- DelegationCascadeTest: P04/P10 descendants, unaffected branches/Cores, restart,
  pending-exit denial, forged issuer rejection, transactional audit and rollback.
- DelegationEnforcementTest: P05, P08, P09 and P10 consumption checks plus additive
  legacy migration without fabricated ancestry.

Full JDK 21 build passed: 139 tests, zero failures/errors/skips, including Detekt
and migration verification. General Trust/Agreement persistence and contract
settlement remain the next P10 work; unknown legacy lineage and distributed
authority projection are separate limits.
