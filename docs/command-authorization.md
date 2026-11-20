# Command authorization audit — 2026-11-20

This is a review of implemented checks and remaining work, not a claim that all
source requirements or Phase 3 are complete. `ProtocolInvocation.actor` is a
caller-supplied value at the service boundary: hosts must authenticate it before
invocation. RightsConstraint can deny an operation, but does not establish an
actor's identity or automatically supply missing authority.

## Command → required standing → implementation → evidence

| Command group | Required standing / implemented checks | Evidence and remaining work |
|---|---|---|
| P01 createPerson | Explicit trusted bootstrap, no account required | Person/Account invariant and Core Loop tests; general identity-proof enrollment remains external |
| P01 createIdentity | Invocation Person equals owner; Person exists; rights check | IdentityService, CoreLoopVerticalSliceTest; host authenticates the Person |
| P01 authenticate | Allowed provider, canonical single-use challenge, verified proof and durable Person binding | AuthenticationVerificationTest; only local-passphrase provider is implemented |
| P01 rotate/revoke credential | Fresh local provider proof, current credential revision, rights check | PersonAccountIsolationTest, CredentialRaceTest, CredentialMigrationTest |
| CONSENT grant/revoke | Person grant with exact affirmation and explicit terms; revocation by stored consenting Person | ConsentExecutionVerificationTest; other commands affecting people still need consent review |
| P02 requestTrust / P03 presentCompetence | Invoking subject equals declared subject; rights check | Core Loop and invariant tests; proposals/declarations only, not approval or durable general Trust/Competence governance |
| P04 root grant | Core/Sota actor equals issuer; no self-grant; same accountability target; valid end window; rights check | AuthorityGrantBoundaryTest, ADR-013; authenticating collective mandates and recipient eligibility remain trusted-host duties |
| P04 delegation | Actor is issuer; one current covering parent and complete valid lineage; context, scope, time and accountability ceilings | DelegationBoundsTest, AuthorityGrantBoundaryTest; no implicit authority from membership or role |
| P04 revoke | Stored issuer, rights check, atomic descendant revocation/audit | AuthorityRevocationBoundaryTest, DelegationCascadeTest |
| P05 createIntent / formMission | Actor equals subject/owner; rights check | Core Loop tests; collective representation and broader mission admission rules remain open |
| P05 decide / execute | Authority holder equals actor; persisted ACTIVE Mission checked at both stages; policy receives actual Mission ID; scope, mission binding, time and ancestor checks; exact persisted Decision at execution; affected-Person consent | MissionStateBoundaryTest (ADR-022), DecisionAuthorityOwnerTest, ConsentExecutionVerificationTest, DelegationEnforcementTest; participation, version binding and mode propagation remain open |
| P05 recordResult | Exact stored Action, original actor and persisted context; rights check | SignedResultTest; signed mode additionally requires exact verified local Action history and a current signing key |
| P06 generic recordEvent | Actor equality and rights check, then unsupported | No general non-action event dispatcher or signed P08 producer |
| P07 ordinary MemoryService | Exact persisted own Events/Experience; distinct same-context nonfuture Events; current CANDIDATE for validation, current CANDIDATE/VALIDATED for author-only deprecation with reason; matching non-Agent validator; persisted policy context; transactional checks/write | MemorySourceBoundaryTest, Core Loop and NoAICanonicalizationTest; ADR-023. Ordinary mode does not establish signed history, lifecycle audit/versioning or general validation mandates |
| P07 SignedP07Runtime | Local non-Agent actor, exact persisted sources and verified history, current key, constrained lifecycle and atomic writes | SignedMemoryTest, SignedMemorySafetyTest, SignedMemoryAtomicityTest; host authentication and collective validation mandates remain external |
| P08 createCore | Invoking Person is founder; rights check; Core and founding membership commit together | MembershipAuthorizationTest, Core Loop; founder receives membership, no implicit owner authority |
| P08 invite | Authenticated target Core/Sota invocation only; rights check; unused membership terms saved as PROPOSED | MembershipLifecycleTest; delegated invitations and collective decision/representation rules remain open |
| P08 accept | Invited Person; exact persisted PROPOSED record, no end, clock at/after start; rights check | MembershipLifecycleTest, MembershipPersistenceTest; unrecorded invitations must be reissued |
| P08AdmissionRuntime invite/accept | Fresh local proof, Person derived from unit binding; representative holds current Core/unit/recipient mandate with recorded target-Core root; invitee separately authenticates; mandate rechecked at acceptance; atomic human audit | AuthenticatedAdmissionTest, AdmissionAuthorityTest, AdmissionAtomicityTest; ADR-021 consumes existing mandates, does not issue roots or represent arbitrary collective commands |
| P08 leave | Member Person; exact persisted ACTIVE record and valid transition; rights check | MembershipLifecycleTest; membership-only transition, not P10 export/settlement or authority revocation |
| P08 remove | Exact ACTIVE record plus ADR-008 actor-bound, collective-scoped current authority and full lineage; rights check | MembershipAuthorizationTest, DelegationEnforcementTest; no authority inferred from a role |
| P09 peer/key/historical provisioning | Independent actor-bound authority, configured context, exact operation/resource, current lineage and rights check | ProvisioningGovernanceTest, HistoricalApprovalTest; initial trust anchors remain explicit host setup |
| P09 exchange | Current peer credential, signature/evidence, origin/relay/context/sharing policy and causal validation | ProductionAdmissionTest, ProductionSharingTest, HistoricalSignatureTest; receipt does not authorize remote business commands |
| P10 exit stages | Exiting Person, stored scoped exit process, valid stage, rights check and verified export before termination | ExitServiceTest, ExitExportTest; third-party exit and general settlement remain open |
| P10 export/fulfillment governance | Independent Person with current lineage, explicit Core/resource scope and matching context; rights check | ExitExportTest, DelegationEnforcementTest |
| Research commands | Fresh provider proof and current Person-bound operation/workspace/unit scope; recheck before commit after waits | ResearchAgentTest and CLI smoke; approvals remain draft-only, separate from canonical Knowledge |

## P08 findings addressed in this increment

The former accept path could save a fabricated Membership without a recorded
invitation. Changed role/start terms could create another row, and replaying a
proposal could restore membership after leave. Removal checked authority but not
the persisted membership being changed. Core creation could leave a Core behind
when the founder's membership insert failed. New regression tests reproduced
these gaps before the implementation changed.

[ADR-020](adr/ADR-020-persisted-membership-admission.md) records the bounded fix:
persisted invitations, direct target-collective issuance, exact current records,
legal lifecycle transitions and shared SQLite transactions. This does not
establish a complete collective governance/authentication protocol.

[ADR-021](adr/ADR-021-authenticated-core-admission.md) adds a concrete authenticated
host for delegated Core invitation and acceptance. It consumes a scoped existing
mandate and records the human representative rather than accepting a claimed Core
actor at its public boundary. Original Protocol/Security constraints were reviewed
and mapped in that ADR; initial root-grant legitimacy remains unresolved.

## Next review slices

1. Specify initial collective mandate/root eligibility and broader representation
   beyond ADR-021's authenticated delegated Core invitations. Reuse the
   confirmed single-founder Research bootstrap only within its ADR-018 scope.
2. Review P05 participation/version/mode rules and P07 validation mandates against
   the original specifications; add concrete source-to-test mappings before
   selecting additional policies.
3. Review remaining person-affecting commands for explicit consent, and ordinary
   memory operations for lifecycle audit, reason retention and version lineage.
   Persisted-source/state checks are implemented under ADR-023; keep signed and
   ordinary host contracts clearly distinguished.

The source references in the existing ADRs and traceability matrix guide this
audit. Full normative source conformance is still open; no new voting, ownership,
automatic membership-based authority or remote admission mechanism is inferred.

P05 Mission existence/state is implemented under [ADR-022](adr/ADR-022-active-mission-boundary.md).
Five regressions reproduced missing/inactive Mission acceptance and omitted policy
Mission ID; six tests cover both execution modes, database reopen, denial before
writes and historical Result reporting. Root approval/quorum remains an explicit
unresolved policy question for the owner.

P07 ordinary source/state checks are implemented under
[ADR-023](adr/ADR-023-persisted-memory-boundary.md), with nine SQLite regressions.
Validation of a current candidate does not itself establish the validator's
collective mandate or produce ordinary lifecycle audit history.
