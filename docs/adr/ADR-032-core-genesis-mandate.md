# ADR-032 — Core Genesis Mandate

Status: **PROPOSED — NOT ACCEPTED / NOT IMPLEMENTED**  
Date: 2026-11-28  
Scope: founding authority, subsequent renewal and withdrawal; candidate schema 1 → 2

## Context and decision boundary

This draft develops the owner's [architecture proposal](../core-genesis-mandate-proposal.md)
published in `6a6c0a59`, linked from the roadmap in `736ceb82`. It does not approve
itself. Production remains **schema 1**; no migration, new principal type, root grant,
founding consent or authority backfill is introduced by this review.

The reviewed Constitution is the Ukrainian **unadopted draft** at
[`430621221c7873936211f6fe0533b451ff50ca76`](https://github.com/Khmelyuk/SOTA-Constitution/tree/430621221c7873936211f6fe0533b451ff50ca76).
“Current” means this repository revision, not an enacted constitution. The
[traceability review](../core-genesis-mandate-traceability.md) distinguishes its
requirements, local axiom engineering notes, the owner's decisions and new proposals.
The Constitution is not changed to accommodate the implementation.

## Proposed authority model

**Genesis Authority Invariant:** a Core cannot originate its own governing powers.
Each exercised power must trace to a voluntary, limited and revocable delegation
by a subject entitled to delegate that particular power. A quorum verifies a
decision; it cannot manufacture missing entitlement or bind nonconsenting outsiders.
This is a design restriction for this voluntary founding protocol, not a claim that
it exhausts every lawful source contemplated by Constitution Article 50.

Keep four objects distinct: the founding agreement, the founding subjects, the
collective constituted by their agreement, and its governing Core/mandate holder.
The domain already distinguishes `CoreId` and `SotaId`; neither a node administrator
nor possession of one of these IDs proves constitutional competence. The precise
mapping of these domain objects requires Q02 below before implementation.

An agreement identifies purpose, scope, resources, participants and admission/exit
rules; its decision policies identify electorate, representation, conflict handling,
renewal, revocation and review. A mandate additionally identifies holder, permitted
actions, delegation sources, ceilings, accountability, validity and cessation rules.
Founding agreement, consent to its terms and delegation of a power are distinct facts.
Agreement acceptance alone cannot supply a missing delegation.

### New collective: one or several founders

1. Prepare a proposal with fresh collective/mandate identifiers and a **fixed,
   nonempty founder roster**. Establish each subject's identity and relevant capacity.
   An account, key, node, membership or self-declaration alone is insufficient.
2. Describe each proposed delegation by source subject, lawful basis, explicit actions,
   resources, recipients, context, purpose, time bounds and redelegation permission.
   Check individual entitlements and fundamental constraints. Existing `Right` rows
   are constraints, not a pool of transferable permissions. Existing P04 parent grants
   can evidence delegated capacity, but cannot be required for every original exercise
   of a founder's own capacity: that would make founding circular.
3. Present the same canonical agreement and mandate revision to **every** roster
   member. Both initial agreement and initial mandate require 100% affirmative consent
   of that defined roster. Absence, silence, abstention and disconnected status are not
   consent. Changing terms or roster invalidates approvals of the previous proposal;
   dissenters cannot simply be removed from a signed revision.
4. Recheck identities, sources, ceilings, consents, expiry, conflicts and relevant
   current revocations at activation. Atomically store evidence, mandate and any
   authorized root linkage; never publish an active mandate from a partial write.
5. Later entrants accept their own admission terms. They do not retrospectively
   become founders or retroactively grant authority over themselves or their resources.

For one founder, step 3 requires one valid affirmative approval, not an exemption.
The holder must remain a distinct collective, with finite and revocable powers.
It gains no jurisdiction over future participants. An existing Sota acting as a founder
must evidence its own lawful representative and bounded source mandate; treating
all its accounts as additional founders would double-count subjects.

Human and digital intellect-subjects have equal conceptual standing in the proposal.
This does not recognize every software agent as a subject. Current `PersonId`-based
Identity/Membership/Consent and ADR-006's inert AGENT remain unchanged. A separately
reviewed subject-recognition/representation design is required (Q01); do not disguise
a digital subject as a human `Person` or silently enable AGENT authority.

### First formal mandate in an existing collective

This is **not genesis replay**. Preserve founding history, membership, old grants,
consents and audit without making them evidence of present root eligibility.
Historical founders have no automatic permanent privilege; current membership alone
also does not prove the capacity to delegate a particular power.

Before formalization, identify the currently applicable agreement, entitled subjects,
valid representatives, present source entitlements and a lawful decision procedure.
The exact electorate and initial formalization threshold remain **unresolved Q03**.
Neither founding unanimity nor the proposed renewal threshold may silently stand in
for this missing procedure. Until resolved, the feature cannot issue a formalized
root. Schema conversion itself supplies none of these decisions.

## Term, renewal and cessation

Proposed mandate validity is finite and half-open: `validFrom <= now < validUntil`.
The agreement sets the duration and decision window; this ADR does not invent a
default number of days. Delegation cannot outlive its source. At expiry, dependent
future actions fail; recording historical results and independently authorized
handover remain possible. No implicit grace period or emergency self-extension.

After founding, the agreement governs quorum. The owner's **proposed standard** for
renewal is `ceil(2 * N / 3)`, where `N >= 1` counts all eligible subjects in the agreed
electorate snapshot, not connected nodes, keys or votes cast. For N=1,2,3,4 the
thresholds are 1,2,2,3. Each subject counts once. Membership changes, recusal and
withdrawal must follow an approved electorate policy, not silently lower N mid-round.
An insufficient renewal vote cannot extend expiry. Whether sustained support below
the threshold suspends a still-unexpired mandate is a **separate Q04 decision**;
a renewal threshold is not automatically a continuous-confidence rule.

Renewal must reference the predecessor and exact new terms. It cannot exceed any
source delegation, add nonconsenting subjects or convert expired sources into live
ones. Expanding scope requires the affected entitlement holders' new explicit
delegations and the agreement's decision procedure; 2/3 alone is insufficient.

A subject can withdraw its own delegation without Core approval. After a valid
withdrawal is known, future exercise of powers dependent on that source, including
descendants, is denied. Preserve the withdrawal and earlier history; do not delete
obligations, pretend past actions never happened, or automatically terminate membership.
P10 exit, agreement termination and mandate-source withdrawal are different operations.
Whether separable remaining powers can continue or the entire mandate must suspend
is Q05; ambiguous dependency means no exercise of the affected power. Whole-mandate
recall follows an explicitly agreed procedure, not an invented 2/3 default. Core may
renounce its powers but cannot veto source withdrawal or remove its own accountability.

Conflict of interest requires disclosure, challenge and independent review. A
founder's interest in founding is not by itself a disqualification that makes
single-subject founding impossible. Adjudicating one's own challenge is different.
Core cannot finally dismiss a challenge to its own competence or reduce the electorate
by declaring opponents conflicted. Independent review does not itself confer enforcement
power; A21 compulsory jurisdiction is outside this voluntary protocol.

## Evidence and enforcement

Candidate logical records are `FoundingAgreement`, `FoundingConsent`, `CoreMandate`,
`MandateScope`, `MandateApproval`, `QuorumPolicy`, `MandateRevocation`, plus explicit
source-entitlement/dependency evidence. These are responsibilities, not approved SQL
tables. A root with multiple sources must preserve a source-to-power dependency map;
the current single-parent P04 lineage alone cannot express it. Neither a blind union
of founders' rights nor a requirement that everyone own every contributed resource
is a substitute for checking each affected power and its actual sources.

Signed approvals bind protocol/domain version, collective and holder IDs, agreement
and mandate revisions/digests, roster/electorate epoch, exact scope and sources,
decision type, predecessor, signer/representative/key ID, nonce and validity window.
Define canonical encoding and test vectors before implementation. Authentication
proves the actor; a signature proves the bound statement; neither proves entitlement.
Reject cross-collective, cross-purpose, cross-revision and expired reuse. Identical
delivery is idempotent and never a second vote. Conflicting approvals remain evidence,
not last-write-wins. Key retirement does not erase verified history, but old-key
historical receipts must not become authorization to sign a new mandate (ADR-012/016).

Evidence is append-only, with explicit corrections, causal parents and provenance.
Store validation outcomes and reasons without exposing private identity evidence or
secret keys in public audit/CI. A Memory Chain preserves provenance; it does not
establish subjecthood or legitimacy, canonicalize Knowledge, or authorize execution.
All authority consumers (P04/P05/P08/P09/P10 and future command hosts) must eventually
check the current root basis inside their mutation transaction, not only at issuance.

Proposed lifecycle: draft → collecting → ready → active; revoked, expired and
superseded cease future exercise. Suspended/contested states deny the affected powers
pending an independently valid resolution. “Ready” is not authority. The active
projection is derived from verified evidence, time and source validity, not a mutable
administrator flag. Precise whole/partial suspension semantics await Q05.

## Autonomous nodes and partitions

A node can prepare proposals and record a subject's signed withdrawal locally.
Single-founder or fully collected founding evidence does not require a vote from
other servers, but activation still requires a valid source and the agreed freshness
policy. No network topology changes the electorate or threshold.

Immediate global revocation cannot be guaranteed while disconnected nodes execute
indefinitely on cached authority. Proposed conservative behavior: deny delegated
operations whose necessary authority freshness cannot be established; retain local
drafting, evidence and operations with an independent valid basis. Bounded offline
leases are an alternative requiring explicit acceptance of the residual revocation
window (Q06), not an asserted solution to the partition problem. Expiry uses an
approved clock/skew policy; rolling back local time must not extend a mandate.

After reconnect, authenticate peers, verify exact signatures and causal dependencies,
then reconcile. Missing parents remain pending. Known valid source revocation prevents
reuse of its older grant; replaying history cannot reactivate it. Concurrent renewal
and withdrawal or incompatible revisions retain both facts, mark the dependent powers
contested and deny use pending valid resolution. Permuting delivery order must yield
the same projection. Neither majority-of-nodes nor last-write-wins resolves authority.
Previously executed offline actions remain truthful historical evidence, with freshness
and conflict context; the system must not fabricate authorization or automatic guilt.

## Acceptance and rollout gates

The [acceptance matrix](../core-genesis-mandate-acceptance.md) is **planned**, not
evidence of implemented governance. The [migration/recovery plan](../core-genesis-mandate-recovery.md)
defines data preservation and CI evidence. Resolve [Q01–Q09](../core-genesis-mandate-questions.md),
record explicit ADR approval and implementation scope, then implement and certify an
actual migration. Approval of this document alone must not issue a mandate.

Rejected shortcuts: host-created unconditional roots; founder privilege forever;
automatic migration-time consent; membership as permission; 2/3 as a source of rights;
silent AGENT promotion; expiry bypass for emergencies; unsigned legacy history
upgraded into signatures; rewriting the Constitution to fit existing tables.

Independent schema-1 operations/P10 work continues through the
[stopped-node recovery rehearsal](../node-backup-recovery.md). No production authority
or Constitution changes are necessary for that work.
