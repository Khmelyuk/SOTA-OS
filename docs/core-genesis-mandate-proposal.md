# Core Genesis Mandate — architecture proposal

Date: 2026-11-28. Status: proposed policy for review; not an accepted ADR or implemented feature.

## Genesis Authority Invariant

No governance structure is the original source of its own authority. Core receives limited, revocable authority through voluntary delegation by subjects. Human and digital intelligences have equal standing in this conceptual mechanism; current MVP actor restrictions are not changed by this proposal.

Founders, founding agreement, Core and mandate are distinct concepts. Resource ownership, node administration and governance authority are also distinct.

## Proposed approval policy

- Founding agreement and genesis Core mandate: affirmative consent of 100% of the explicitly identified founders, not merely those present.
- A single founder may establish a Sota, without automatic authority over future members.
- Subsequent collective decisions follow the founding agreement. Proposed default for renewal: at least two thirds of eligible participants (round up), within the authority actually delegated.
- Changes and collective revocation follow the agreed policy; each subject retains the right to withdraw its own delegation without Core permission.
- Voting alone cannot authorize infringement of fundamental freedoms or create authority that delegators do not possess.
- No self-expansion, permanent founder privilege, automatic administrator authority or emergency powers arising merely from loss of quorum. Expired mandates cease to authorize actions.

The initial root mandate in an **already existing Core** is a distinct case from genesis. Do not treat historical founders or current members as automatically eligible issuers. Establish explicit current consent, eligibility and applicable agreement before issuance; the precise approval policy for legacy Cores remains open. Renewal threshold, single-founder option and quorum mechanics require explicit policy confirmation and source traceability.

## Candidate for first functional schema 1 → 2 migration

Logical candidates (not prescribed physical tables): FoundingAgreement, FoundingConsent, CoreMandate, MandateScope, MandateApproval, QuorumPolicy, MandateRevocation. Reuse existing domain concepts where possible.

Migration is not delegation: preserve existing rows and history without manufacturing consent, signatures, root mandates or new privileges. Keep production schema 1 until ADR and release acceptance are approved. Define how existing legitimate authority is preserved without inventing new evidence.

## Implementation assignment

1. Draft an ADR covering issuance, approval, scope, duration, renewal and revocation; trace to axioms, Constitution and original architecture sources. Do not change the Constitution to simplify implementation.
2. Review Identity, Membership, Authority, Consent, Audit and Memory Chain; reconcile with ADR-013, ADR-020 and ADR-021, including existing-Core root eligibility.
3. Specify single/multiple-founder creation, exact signed agreement and mandate terms, eligible electorate and quorum denominator, conflict of interest and replay protection.
4. Evaluate this feature as the first real numbered migration under ADR-030/031. Preserve populated historical and current profiles, verify reopen, interrupted upgrade and retry, transactional guards and version marker.
5. Test withdrawal, expiry, quorum loss, self-escalation, stale/replayed approvals, cross-Core scope, offline operation, partitions and deterministic synchronization. Deny unauthorized actions without silently resolving contested authority.
6. Define named source/target release identities, supported upgrade paths, backup/restore procedure, acceptance matrix and CI evidence bundle. No release claim before evidence.
7. Continue independent P05/P07 review, P09 operations and P10 work without assuming unresolved governance policy.

## Open questions

Continuity of governance, resources and outstanding obligations after mandate loss; relationship to P10 settlement and arbitration; existing-Core approval policy; exact quorum/eligibility and renewal semantics. Revocation of delegation does not by itself erase obligations.

See [roadmap](roadmap.md), [authorization audit](command-authorization.md), [upgrade matrix](release-upgrade-matrix.md). Source conformance and implementation have not yet been verified for this proposal.
