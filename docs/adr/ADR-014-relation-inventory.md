# ADR-014: Durable relation terms and Core-scoped participation

Status: Accepted — 2026-09-30

## Context

Data Model v0.1 §§19–20 describes contextual, evidence-based Trust and voluntary
Agreements with parties, terms, validity, obligations and exit conditions.
Protocol Architecture v0.1 §13 separates closing relations from settling obligations.
The previous P10 inventory stored only a kind and description, with no typed terms
or durable association between an agreement and its responsibilities.

## Decision

Add `RelationInventory`, implemented by `SqlDelightRelationInventory`, as a trusted
infrastructure import port. A stable ID identifies one person's participation in
one Core, not a globally synchronized multiparty contract. Import requires active
Core membership. Outgoing Trust must belong to that person; an Agreement must name
that person among at least two distinct parties and include nonblank purpose,
terms and exit terms. The host must already have established the relationship and
verified any necessary consent. Import is not an authenticated enrollment endpoint
and is not exposed as a CLI command or through P09.

Persist the original typed terms immutably, alongside the existing scoped exit
inventory. Trust preserves context, scope, evidence and validity. Agreement preserves
parties, purpose, terms, validity and exit terms. Agreement import explicitly chooses
`WITHDRAW_PARTICIPATION_RETAIN_OBLIGATIONS`, the only supported executable exit policy.
The host may select it only for agreements compatible with that policy. Free text
is retained, never interpreted as executable contractual restrictions.

Insert the participation, terms, new obligations and immutable obligation links in
one transaction. Duplicate identifiers, invalid obligations or pending-exit guards
roll back the whole import. An obligation belongs to one imported participation;
existing inventory obligations are not silently reassigned. Linked obligation
identity, owner, Core and description cannot be rewritten or deleted. Retention and
independently governed fulfillment use the existing P10 path.

P10 closes scoped participation in its existing audited transaction. Original terms
remain intact, including the original lifecycle state; the separate participation
state becomes CLOSED. This is not global termination of a multiparty agreement or
proof that any duty has been fulfilled. Remaining duties become RETAINED during
settlement; closure does not erase or fulfill them. Closed typed participation
cannot be reopened. Other Cores remain unaffected.

Portable obligations include their relation ID. Shared Trust/Agreement terms are
not automatically copied into an archive or synchronized. The archive remains a
frozen snapshot; later fulfillment does not rewrite an already delivered archive.

## Migration and evidence

Additive startup migration creates terms/link tables and guards. Existing generic
inventory rows remain usable; no parties, consent, terms or obligation links are
invented for legacy data. Fresh databases use the same SQLDelight table definitions.

`RelationInventoryTest` covers restart, P10 closure/retention, archive provenance,
atomic import rollback, pending/completed exit rejection, participant/scope
validation, immutable terms and duties, audit rollback, Core isolation and additive
migration. Existing exit, governance and signed-P09 tests remain applicable because
these relations share the existing P10 inventory and transaction boundary.

## Limits

Multiparty proposal/consent workflows, agreement amendments, alternative executable
exit policies, external enforcement or payment settlement, global relationship IDs,
remote relation projection and legal evaluation remain future work. This change
implements durable local participation inventory, not the full SOTA Handshake.
