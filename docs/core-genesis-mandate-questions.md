# Core Genesis Mandate — decisions requiring separate discussion

Status: OPEN; companion to proposed [ADR-032](adr/ADR-032-core-genesis-mandate.md).
No row is resolved by publishing this file. No Constitution amendment is proposed.

| ID | Issue and evidence | Proposed direction / decision needed | Gate |
|---|---|---|---|
| Q01 | Constitution I–II recognizes intellect-subjects without a substrate exclusion; local A01/A14 engineering notes and ADR-006 implement human primacy / AI ≠ PRINCIPAL. Current Identity, Membership and Consent use PersonId | Separate recognized subject from account, technical AGENT and representative. Determine recognition/appeal and identity continuity for digital subjects, then explicitly reconcile the implementation policy. Do not rewrite constitutional equality or automatically recognize a model | Subject model and any digital founding |
| Q02 | Constitutional SOTA, organizational governing Core, domain Core (small collective) and domain Sota (group of Cores) are not synonyms | Approve an explicit mapping of constituted collective, governing holder, node/unit IDs and nested representation; decide whether a bounded first migration can avoid introducing a new collective type | Schema and command design |
| Q03 | An existing Core may have historic founders, members and host-issued roots but no verified current constituent procedure | Establish current agreement, entitled subjects, source capacities and formalization procedure. Keep legacy history; neither 100% historic founders nor 2/3 current members is presumed sufficient | Existing-Core formalization; no automatic backfill |
| Q04 | 100% genesis is the owner's requirement; 2/3 renewal is proposed. Constitution Art. 54 does not prescribe either number | Approve renewal electorate, membership changes, representation, recusal, ties/abstention, decision window and whether loss of continuing confidence suspends an unexpired mandate. Freeze each ballot snapshot; never quietly shrink denominator | Quorum lifecycle |
| Q05 | Art. 52 supports revocation; individual source withdrawal need not be identical to dissolution or whole-mandate recall | Define whole/partial suspension and separability, whole-mandate recall procedure, finite handover and accountability. Withdrawal of one's own source requires no holder permission | Revocation, descendants, P10 integration |
| Q06 | A disconnected node cannot know about a new remote revocation | Choose which operations require fresh evidence and from whom, maximum clock uncertainty, and whether bounded offline execution risk is acceptable. Conservative proposed default denies dependent operations without required freshness | Offline activation, execution and restored-node rejoin |
| Q07 | Current P04 requires a parent for Person/Agent issuance and trusts collective root standing; Right is a constraint, not a transferable grant | Model original capacity/source evidence separately from previously delegated Authority, with provenance, challenge and exact per-resource ceilings. Do not solve bootstrapping with a fabricated grant | Root eligibility and migration linkage |
| Q08 | Old roots remain usable under the old trusted-host contract, but cannot truthfully be labeled genesis-verified | Approve a cutover policy and operator review: preserve records, distinguish unresolved basis, gate new mandate-dependent operations, define transition for existing consumers. Neither silently bless nor silently mass-revoke legacy powers during DDL | Production rollout and backward compatibility |
| Q09 | Articles 59/62/98 require independent review; founders and mandate holders can have interests. A21 remains open | Define review/recusal and representation without inventing enforcement powers or requiring every sole founder to be disinterested. Decide protection, retention and access to private capacity/recognition evidence | Disputes, audit and review |

The human-centred wording in the reviewed axiom notes and the broader constitutional
subject model are a **source/policy tension**, not a license to select a convenient
precedence silently. This review does not decide whether the notes have been superseded.
The Constitution repository calls itself an unadopted draft; claiming enacted force
would be a separate error. Resolve source standing and subject policy explicitly.

Review should record answers, responsible decision makers, accepted text/revision,
scope and date. An engineering merge or green CI is not that approval. Q03/Q08 may
be deferred only by explicitly excluding existing-Core activation from the first
feature; its data-preservation tests must still be supplied. Unresolved questions
must not become hidden runtime defaults.
