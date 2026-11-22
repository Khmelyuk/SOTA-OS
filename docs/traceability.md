# Traceability Matrix — Code ↔ Specification

P07 version update 2026-11-22: MemoryService.reviseKnowledge and KnowledgeLineage
connect Memory §§13–14/18–19 to separately reviewed candidates, immutable stored
content/links, retained predecessors and explicit stale/fork denial. Local and signed
audit retain reason, source and version history atomically. KnowledgeLineageTest,
KnowledgeRevisionTest and KnowledgeRevisionBoundaryTest cover persistence, source
boundaries, lifecycle, rollback, restart and signed evidence. SignedMemoryTest delivers
revision and validation through recovery without remote entity materialization.
Source pages/fingerprints and limits are in [ADR-026](adr/ADR-026-knowledge-version-lineage.md).
General branch/conflict governance and validation mandates remain open.

Local P07 audit update 2026-11-21: MemoryAudit, LocalMemoryAudit and
SignedMemoryAudit connect Memory §§13/27/34/43 (bounded M-10 history evidence)
to atomic entity/Event recording. LocalMemoryAuditTest covers actor attribution,
full snapshots/reasons, restart, append-only storage, rollback/retry, denial and
legacy transitions. Source pages/fingerprints and limitations are in
[ADR-025](adr/ADR-025-local-memory-audit.md). Signed payload/causal semantics are
preserved without duplicate local Events. ADR-026 subsequently adds bounded version
lineage; general validation mandates remain open.

P05 mode update 2026-11-21: `MissionActionService` copies the current persisted
Mission mode into Action and ACTION_EXECUTED; `SignedResultRecording` checks it
against signed evidence when present. Data Model §§16/27/50 and Protocol §21
sections/pages/fingerprints are mapped in [ADR-024](adr/ADR-024-mission-action-mode.md).
MissionModeTest covers both modes/paths, reopen, snapshot history, mismatch denial,
legacy compatibility and rollback. SignedActionTest exchanges PROTECTION history
after lost response/restart without remote execution. Mode-change governance and
Decision version binding remain open.

Ordinary P07 update 2026-11-20: `MemoryService` checks exact persisted sources,
Event sets and current Knowledge states inside a shared repository transaction.
`MemorySourceBoundaryTest` maps Protocol §10 and Memory Architecture §§9–10/27/43
(M-02/M-03) to denial, restart and write rollback. Sources/pages/fingerprints and
remaining audit/version/mandate limits are in [ADR-023](adr/ADR-023-persisted-memory-boundary.md).
Ordinary unsigned facts remain local; exact storage equality does not establish
signed history, canonicality or full M-05/M-10 conformance.

P05 Mission update 2026-11-20: `MissionActionService` loads a persisted ACTIVE
Mission at decision and execution, and passes its ID to decision rights policy.
Protocol §8, Data Model §27 and Security §14 sections/pages/fingerprints are mapped
in [ADR-022](adr/ADR-022-active-mission-boundary.md). `MissionStateBoundaryTest`
covers missing/inactive Missions, ordinary/signed execution after reopen, no writes
on denial, actual policy Mission ID and historical Result reporting after closure.
Participation and version binding remain open; mode propagation was added under ADR-024.

Authenticated admission update 2026-11-18: `P08AdmissionRuntime` connects P01
provider proof to P04 scoped representation and P08 explicit agreement. Original
Protocol §7/§11 and Security §§13–14/22/29 constraints, pages and fingerprints are
mapped in [ADR-021](adr/ADR-021-authenticated-core-admission.md). AuthenticatedAdmissionTest,
AdmissionAuthorityTest and AdmissionAtomicityTest verify proof/replay boundaries,
Core/unit/recipient scopes, current lineage, human audit and transactional recovery.
Initial collective mandates and general representation remain open.

P08 authorization update 2026-11-18: `CollectiveService` now persists invitations,
requires target-collective issuance and exact current membership records, rejects
stale transitions, and commits Core/founder creation atomically. MembershipLifecycleTest,
MembershipAuthorizationTest and MembershipPersistenceTest cover CORE-12 voluntary
association and the bounded ADR-008/020 admission/removal contract, including
restart, legacy IDs, denial and rollback. [Command audit](command-authorization.md)
records remaining collective authentication and source-conformance work.

Signed P07 update 2026-11-18: `SignedP07Runtime` and `JsonMemorySnapshotCodec`
connect P07/AC-10/11 memory provenance to P09/AC-13 delivery. `SignedMemoryTest`,
`SignedMemorySafetyTest` and `SignedMemoryAtomicityTest` cover complete snapshots,
causal parents, local source verification, human transitions, no-Agent validation,
key rotation, rollback, restart and two-node replay. The HTTPS rehearsal now
exchanges ten facts without remote business projection. See [ADR-019](adr/ADR-019-signed-memory-producers.md)
and the [producer limits](p07-signed-memory.md); general collective authorization
and source conformance remain open.

Integration review 2026-11-17: `AuthorityGrantBoundaryTest`,
`AuthorityRevocationBoundaryTest`, `DecisionAuthorityOwnerTest` and
`SqlDelightAuthorityBoundaryTest` add 14 regressions for validity/accountability,
stored revocation terms, decision ownership and SQLite reopen. The current
development order is recorded in [roadmap](roadmap.md).

Per Master Prompt §16. Every row must be answerable; entries left as
`TBD` block Phase 3 completion for that component.

| Code Artifact | Architecture Core | Data Model | Protocol | MVP Req | Test |
|---|---|---|---|---|---|
| `domain/identity/Identity.kt::Person` | CORE-01 Human Primacy | §5 PERSON | P01 | MVP-01 | `acceptance/PersonAccountIsolationTest` (local provider lifecycle) |
| `domain/identity/Identity.kt::Account` | CORE-01 | §7 ACCOUNT | P01 | MVP-01 | Domain type only; general Account persistence remains open |
| `domain/collective/Collective.kt::Core` | CORE-09/10 Self-Organization / Small Core | §22 CORE | P08 (membership) | MVP-03 | `acceptance/AC02_CoreWithoutAdminTest` |
| `domain/collective/Collective.kt::Sota` | CORE-11 SOTA as Autonomous Unit | §24 SOTA | P08, P11(stub) | — | `architecture_invariants/NonOwnershipTest` |
| `domain/relation/Relation.kt::Trust` | CORE-05 Trust-based Coordination | §19 TRUST | P02 | MVP-04 | `architecture_invariants/TrustNotScoreTest` |
| `domain/relation/Relation.kt::Competence` | CORE-06 Competence-based Authority | §12 COMPETENCE | P03 | MVP-04 | `architecture_invariants/CompetenceEvidenceTest` |
| `domain/relation/Relation.kt::Authority` | CORE-07 Authority is Delegated | §14 AUTHORITY | P04 | MVP-04, AC-05, AC-06 | `acceptance/DelegationBoundsTest`, `DelegationCascadeTest`, `DelegationEnforcementTest`; ADR-013 |
| `domain/agency/Agency.kt::Action` | CORE-03, CORE-17 Decision Traceability | §16 ACTION | P05 | MVP-05, AC-15 | `architecture_invariants/NoActionWithoutAuthorityTest` |
| `security/RightsConstraintDecorator` + `application/ports/RightsConstraint` | CORE-02 Human Dignity Constraint | RIGHT §8-9; Rights Layer §15 | Protocol Architecture §16 | MVP §11, AC-07/15 | Protocol-neutral guard evaluated on state-changing service commands P01-P08; explicit RIGHT and purpose/context/scope-bound CONSENT records now have domain and SQLite persistence; consent is not yet consulted by every action affecting a Person |
| `domain/agency/Agency.kt::Result` | CORE-04 Creation Orientation | §32 RESULT (ACTION != RESULT) | P05 | AC-09 | `unit/ActionResultDistinctTest` |
| `domain/memory/Memory.kt::Event` | CORE-16 Event as Fundamental Fact | §31 EVENT | P06 | MVP-06, AC-14 | `architecture_invariants/EventImmutabilityTest`; ADR-003a Ed25519 and P09 signature-admission tests |
| `domain/memory/Memory.kt::Experience` | CORE-18 Memory is First-Class | §33 EXPERIENCE | P07 | MVP-06 | `unit/EventNotExperienceTest` |
| `domain/memory/Memory.kt::Knowledge` | CORE-19 Knowledge -> Capability | §34 KNOWLEDGE | P07 | MVP-06, AC-11 | `architecture_invariants/NoAICanonicalizationTest` |
| `domain/agency/Agent.kt::Agent` | CORE-21 AI is an Agent, not Principal | Agent/AI §51 | P12 (stub) | MVP §12, AC-16 | `architecture_invariants/NoAgentSelfEscalationTest` |
| `application/protocols/ExitProtocol` + `application/services/ExitService` | CORE-13 Exitability | §51 Exit | P10 | AC-18 | `acceptance/ExitServiceTest`, `ExitExportTest`; durable self-exit, governed export and retained obligations; [limits](p10-exit.md) |
| `persistence/Schema.sq::event` + `SqlDelightEventStore` | CORE-16, CORE-26 Resilience | §54 Immutable history | P06, P09 | AC-12, AC-13 | `integration/SqlDelightCoreLoopIntegrationTest` (SQLite reopen persistence) |
| `sync/SyncService`, `DeterministicMerge`, `persistence/Sync.sq` | CORE-14/15 Local Autonomy, Offline | §56 Sync | P09 / ADR-004 | AC-12/13/17: see source mapping; AC-17 uses LocalAutonomyAcceptanceTest | `sync/SyncConflictPreservationTest`, `SyncRecoveryTest`, `SyncAtomicityTest` |
| `protocol/JsonSync*`, `sync/HttpsSyncTransport` | CORE-14 Local Autonomy | ADR-005 | P09 | JSON and HTTPS exchange | `sync/SyncValidationTest`, `HttpsSyncTransportTest` |
| `SqlDelightAuthorityRepository` contested-state read | CORE-07 Scoped Authority | ADR-004 | P04/P05/P09 | No execution with contested authority | `sync/SyncAuthorityBoundaryTest` |
| `api/cli/Main.kt` | MVP local interface / Core Loop | ADR-007, MVP Spec §15 | CLI composition root | Core Loop smoke run | Manual SQLite run: `init` + `demo` |

## Status update — first vertical slice

Implemented; full local build including Detekt and the current test suite passes
on 2026-09-26. CLI smoke and two-node SQLite/HTTPS sync are verified;
broader coverage remains open:
- [x] `domain/*` full slice model
- [x] `application/services/*` implementing P01, P02-04, P05, P06-07
      for the slice
- [x] `test/acceptance/CoreLoopVerticalSliceTest` — full MVP Spec §15
      scenario, AC-01,02,04,05,06,07,09,10,11
- [x] `test/architecture_invariants/CoreInvariantTests` — AC-15, AC-14
      (hash and signature scheme implemented; formal risk mapping remains), AC-16,
      Person≠Account, delegation ceiling / no self-escalation

## Open TBDs (must be closed before Phase 3 sign-off)

- [x] `persistence` SQLDelight adapter classes compile against the
      generated API; one T3 Core Loop persistence test passes, with
      broader adapter and migration coverage still open
- [x] `sync/` durable P09 exchange and deterministic causal merge; JSON
      codecs, HTTPS/REST client and server-neutral handler are tested
- [x] P09 bounded HTTPS pilot host and CLI serve/once under ADR-015
- [x] P09 background retry loop and combined node lifecycle under ADR-017
- [ ] P09 public-hosting operations; peer admission/credential provisioning is implemented under ADR-011;
      additional signed entity producers and automatic historical attestations. See
      [P09 scope](p09-sync.md) and [original-criteria mapping](ac-p09-mapping.md)
- [ ] `security/RightsConstraintDecorator` (Protocol Architecture §16)
      has a protocol-neutral request shape and is called for state-changing
      P01-P08 service commands; P05 execute checks authority/scope first.
      P08 removal now checks active, actor-bound, collective-scoped authority
      (ADR-008). RIGHT/CONSENT persistence and P05 consent checks are tested;
      remaining command-specific authorization is still open
- [x] Concrete signature scheme for `Event.signature` — ADR-003a fixes
      JDK Ed25519 over lowercase SHA-256 `contentHash`; R0/R1 may remain
      unsigned and configured P09 admission verifies actor keys
- [ ] General `protocol/` P01-P10 wire-envelope/dispatch layer; P09 has
      its own versioned JSON exchange envelope
- [x] `api/cli` — `help`, `init`, and the Core Loop `demo` command are
      implemented; `init` + `demo` manually run against SQLite
- [x] `test/integration/SqlDelightCoreLoopIntegrationTest` — persisted
      Core Loop records survive closing and reopening SQLite
- [x] Full local `./gradlew build --continue --console=plain` with JDK 21:
      Detekt, migration verification and the current test suite pass; CI
      workflow is present and its first remote run must remain green

## Production P09 update — 2026-09-26

| Artifact | Boundary | Tests |
|---|---|---|
| PeerTrustPorts / PeerTrust.sq / SqlDelightPeerTrustRepository | Durable trust, credential verifiers, append-only operator/authority audit | PeerTrustPersistenceTest |
| ProvisioningAuthorization / PeerProvisioningService / ActorKeyProvisioningService | Independent scoped authority, no self-provisioning, revision/key guards | ProvisioningGovernanceTest |
| ProductionPeerAdmission / SyncRecordIntegrity / EventSignatureAdmission | Signed content and provenance, exact contexts, allowed origins and assertion entities | ProductionAdmissionTest, ProductionSharingTest |
| api.sync.P09Runtime | SQLite-loaded ActorKeyDirectory, current credentials, transactional inbound policy | ProductionAdmissionTest, PeerTrustPersistenceTest |

JDK 21 CI already exists and runs full build, Detekt, migrations and tests.
AC-12/13/17 are mapped to the original MVP Specification §16; see
[acceptance evidence and limits](ac-p09-mapping.md). This update does not mark the entire
P09 or Phase 3 complete. Public-hosting operations, legacy producer adoption,
automatic historical attestations remain open. Durable local P10 is implemented under ADR-010;
typed local relationship inventory is implemented under ADR-014; broader contract policies
remain open. Recorded delegation lineage is implemented under ADR-013.

## Durable P10 update — 2026-09-26

ExitService and ExitGovernanceService enforce self-exit and independent
export/fulfillment authority. SQLite adapters persist each atomic stage, append-only
audit/event/archive and Core-scoped mutations. P10Runtime connects the adapters to
authenticated CLI exit; file delivery precedes termination.
[Source-to-step evidence and limits](p10-exit.md) map AC-18 without claiming the
entire protocol family or Phase 3 is complete.

## Signed P10 producer — 2026-09-28

ExitEventRecorder / SignedP10Runtime bind newly created P10 events to the strict
P09 signature profile and causal predecessor. Actor keys are loaded from SQLite;
exit mutations, event and journal share one transaction. SignedExitTest covers
production exchange after lost acknowledgement/restart, key and policy denials,
rollback and immutable legacy history. AC-13 evidence now includes signed P10
facts; remote termination projection remains open. ADR-016 adds locally governed historical recovery.

## Historical signature evidence — 2026-09-29

VerifiedRecordSignatures, VerifiedRecordRepository and verified_sync_record
preserve exact locally verified history across key retirement. Event/journal/receipt
commit together, including signed P10 transitions. HistoricalSignatureTest maps
P09/AC-13 recovery to rotation, revocation, restart, replay and rollback without
trusting sender timestamps. See ADR-012 for local-only receipts, migration and
cross-node recovery limits. Current peer policy remains mandatory.

## Authority lineage and cascade — 2026-09-29

Authority.parentAuthorityId, AuthorityLineage and AuthorityCascade implement
P04/CORE-07 explicit delegation bounds and P10/AC-18 subtree cleanup. SQLite
preserves parents, prevents reparenting, indexes child lookup and audits every
revocation atomically. Mandatory ancestry checks cover P05/P08/P09/P10 consumers.
DelegationBoundsTest, DelegationCascadeTest and DelegationEnforcementTest provide
16 regressions: bounds, cycles, forged issuer, independent branches, Core isolation,
restart, rollback, audit, migration and consumer enforcement. Contract settlement
and recovery of genuinely unknown legacy ancestry remain outside ADR-013.

## Typed Trust/Agreement inventory — 2026-09-30

| Source | Implementation | Evidence |
|---|---|---|
| Data Model §§19–20: Trust / Agreement | RelationInventory, SqlDelightRelationInventory, immutable typed terms | RelationInventoryTest: round trip, restart, validation, migration |
| Protocol §13: close relations separately from settlement; AC-18 | Core-scoped participation closure, immutable obligation links, explicit retention policy | RelationInventoryTest: closure, duty retention, Core isolation, rollback, archive relation ID |

ADR-014 distinguishes trusted import from multiparty consent. Textual terms are
preserved without pretending to execute arbitrary contractual conditions.

## Production HTTPS pilot — 2026-09-30

| Source | Implementation | Evidence |
|---|---|---|
| P09, ADR-005, AC-13 | P09HttpsServer + strict P09Runtime + HttpsSyncTransport | HttpsProductionPilotTest: signed two-node exchange, lost response, both stores reopened, reverse exchange |
| Security boundary, ADR-011 | Existing governed registry/key loading behind TLS | HttpsProductionPilotTest: rotated/revoked credentials survive restart |
| Hosting input boundary, ADR-015 | Bounded body, deadline, explicit PKCS12 keys/trust, serialized handler | P09HttpsBoundaryTest and P09TlsTest |

The [runbook](p09-https-pilot.md) documents CLI commands. ADR-017 extends the original exclusive-store pilot with
combined hosting and shared local access; public connection controls remain open. ADR-016
adds local exact-record historical trust decisions, without automatic attestations.

## Governed historical import — 2026-09-30

| Source / decision | Implementation | Evidence |
|---|---|---|
| P09 / AC-13, ADR-016 trust decision | HistoricalRecordApprovalService and P09Runtime.historicalApprovals | HistoricalApprovalHttpsTest: unknown old-key rejection, reviewed exact record, HTTPS recovery after restart |
| Scoped delegated authority and independent governance | ProvisioningAuthorization, digest-specific history.approve/history.revoke, immutable operator audit | HistoricalApprovalTest: scope, self-approval, inactive authority, RightsConstraint, local producer denial |
| Immutable history, ADR-012/016 | Separate approval/revocation tables and linked verification receipts | HistoricalApprovalDurabilityTest: migration, audit/receipt/export rollback, append-time recheck |

The evidence reference is recorded, not automatically verified against an external
source. Governance accepts one exact record/key binding; it does not establish a
trusted timestamp or reactivate a retired key for other records.

## Background sync and lifecycle — 2026-09-30

| Source / decision | Implementation | Evidence |
|---|---|---|
| P09 / AC-12/13/17, ADR-017 | P09SyncLoop and P09NodeHost | P09NodeHostTest: simultaneous HTTPS hosts, local recording, offline recovery, lost reply/reopen, pagination |
| Atomic reconciliation and local autonomy | Store-owned local-access gate, separate prepare/network/complete phases | SyncConcurrencyTest: incoming commit during outgoing wait, stale response cannot regress cursor |
| Bounded retry and controlled shutdown | One outbound worker, capped backoff, interruption and joined workers | SyncLoopLifecycleTest: settings bounds, revocation before network, stopped retries |

No central scheduler or alternate durable queue is introduced. The SQLite journal
and peer checkpoints remain authoritative; in-memory loop status resets on restart.

### P09 operational diagnostics — 2026-10-01

`PeerSyncStatus` exposes attempt phase, observed timestamps, retry schedule and
safe failure categories through the existing local host snapshot API.
`SyncDiagnosticsTest` covers wrapped TLS/network failures, timeout, HTTP rejection,
local validation, unknown failures and in-flight shutdown. `P09NodeHostTest`
checks that recovery clears the failure and records a successful exchange.
These diagnostics support AC-13 operations; they add no new admission authority
and are not evidence of global convergence or inbound health.

### P09 CLI diagnostics — 2026-10-01

`sync run --status-interval-seconds N` exposes safe local snapshots on stdout.
`SyncStatusOutputTest` covers unknown checkpoints and UTC backoff output.
`scripts/p09-cli-status-smoke.py`, also run in JDK 21 CI, verifies argument bounds,
quiet defaults, periodic status, credential exclusion, SIGTERM and same-port restart.
No admission or checkpoint semantics change.

### Signed P10 CLI — 2026-10-01

`exit leave` can load explicit Ed25519 PKCS12 signing material bound to the
authenticated Person's active SQLite key. No provisioning authority is inferred
from possession of a keystore. `P10SigningKeysTest` covers loader denial and restart;
`p10-signed-cli-smoke.py` covers terminal confirmation, delivery/restart, immutable
mode/origin and six signed stages. Existing `SignedExitTest` verifies P09 exchange.
This extends AC-18 integration, without adding multiparty settlement or rewriting history.

### P09 node configuration — 2026-10-01

`P09NodeConfiguration` and CLI `--config` provide bounded, versioned non-secret
settings with strict fields and paths relative to the file. `NodeConfigurationTest`
covers parsing, modes, invalid endpoints/timers, secret fields and input limits.
The existing CLI smoke also launches from a config file and rejects overrides.
This is restart-based configuration, not provisioning authority or live reload.

### P09 outbound metrics — 2026-10-02

`SyncMetrics` records cumulative outcomes and monotonic attempt durations in the
existing local snapshots and CLI status output. `SyncMetricsTest` verifies recovery,
shutdown cancellation, snapshot retention and process-local reset. CLI smoke checks
metric field presence. Metrics observe AC-13 recovery without altering admission,
durable checkpoints or asserting inbound/node-wide health.

### P09 inbound metrics and JSON export — 2026-10-05

`InboundMetricsRecorder` observes handler starts, completions, response codes and
aborts without changing admission. HTTPS boundary tests verify rejection/success
counts and stalled-body recovery. `NodeMetricsJsonTest` and CLI smoke verify
version-1 aggregate JSON export without request/peer labels or a new listener.
These are local process observations, not proof of remote receipt or convergence.

### P09 material rotation — 2026-10-07

`P09Secrets` supplies explicit environment/file sources and per-exchange token
reads. `sync check` validates local candidate material without opening SQLite or
a listener. HTTPS rotation tests connect AC-13 to governed token replacement,
old-token denial, peer revocation, TLS replacement and checkpoint-preserving
restart. Certificate issuance and remote secret distribution are external duties.

### P09 scheduling reload — 2026-10-08

Opt-in local file reload can change idle interval and maximum retry backoff only.
The full candidate is validated against immutable startup inputs before publication.
ConfigurationReloadTest, SyncSettingsUpdateTest and CLI smoke cover rejection,
recovery, an in-flight exchange, pending timer preservation and shutdown. Admission
and durable checkpoints are unchanged; TLS/identity updates still require restart.

### Signed P05 action producer — 2026-10-09

`SignedP05Runtime` connects the authorized P05 action/Event invariant (AC-14/15)
to strict P09 recording and AC-13 recovery. `SignedActionTest` checks lost replies,
reopen/replay, current-key binding and atomic action/event/journal/receipt rollback.
Consent, persisted-decision and authority checks remain enforced. Remote receipt
does not execute an action. This covers new action facts only; the rest of Core
Loop producer adoption and unsigned history remain open. See
[signed P05 scope and host contract](p05-signed-actions.md).

### P01 lifecycle and architecture verification — 2026-10-10

LocalCredentialLifecycleService replaces the former no-op credential methods with
fresh provider proof and current-revision checks. PersonAccountIsolationTest,
CredentialRaceTest and CredentialMigrationTest demonstrate real verifier changes,
Person/Identity preservation, rollback, concurrency denial and durable migration.
The P01 CLI smoke verifies terminal rotation/revocation and secret exclusion.
See [scope and compatibility](p01-credential-lifecycle.md); general Account lifecycle
is not claimed complete.

`verifyModuleLayering` inspects actual Gradle project dependency declarations and
rejects forbidden production edges, unknown modules and cycles. Every module's
`check` depends on it. Test placement across the ADR-007 T1–T4 tiers remains open.

### Reproducible two-node rehearsal — 2026-10-10

`./scripts/two-node-core-loop.sh` adds integrated evidence for AC-12 (offline action
and exit), AC-13 (strict HTTPS exchange, lost acknowledgement, store/listener restart
and replay), AC-17 (two local stores without a central service), and AC-18 (signed
exit stages, verified private export, revocation, closure and termination).
The fixture checks eight matching signed records and durable cursors on both nodes;
remote history does not execute business commands. Initial provisioning trust anchors
and shared Core state are explicit fixtures. This does not claim general production
bootstrap, physical multi-host deployment, or all Core Loop producers. See the
[one-command runbook](getting-started.md). CI runs the rehearsal separately from Kotest.

### Signed Result producer — 2026-10-11

`SignedResultTest` connects persisted P05 Results to canonical `RESULT_RECORDED`
events with the signed Action as causal parent (AC-09 traceability and AC-13 recovery).
It verifies strict sync after lost response/reopen without remote business execution,
current-key binding after Action-key rotation, exact persisted-Action validation,
rights enforcement and atomic rollback. The HTTPS rehearsal now exchanges Action,
Result and six P10 stages. Experience/Knowledge producers and legacy unsigned
history remain outside this change. See [P05 producer contract](p05-signed-actions.md).

### Research workspace — 2026-10-11

`agent/research` is an isolated, human-invoked observe/propose component under
ADR-006; ADR-018 extends the ADR-007 graph only with api → agent. A01–A20 remain the constitutional
baseline; A21 remains draft. ResearchAuthorization uses ADR-009 provider proof,
current actor-bound scoped authority, AuthorityLineage and the required rights
constraint. ResearchJournal preserves source revisions, provenance and local audit;
all knowledge remains candidate. Immutable draft approval binds exact SHA-256
and does not publish or authorize an Action. Moltbook rejects regional blocking
without alternate routes. `ResearchAgentTest` verifies provider replay/proof clearing,
revocation/scope/expiry, restart/deduplication, evidence, approval and blocked ingestion.
The journal is not canonical P06/P09 history; GUI and LLM extraction remain open.
The local CLI is implemented under ADR-018 below.
See [research scope and host contract](../agent/README.md).

### Research CLI composition — 2026-10-11

ADR-018 allows only the additional api → agent edge. Research CLI uses fresh
provider proof, existing RightsConstraint and AuthorityLineage for every command.
A confirmed new single-founder Core bootstraps a 24-hour bounded grant with a
human-attributed setup Event. Voluntary own-grant surrender cascades revocation
and records human audit without changing P04's issuer-only revoke contract.
Exact draft preview/hash confirmation stays draft-only. The terminal smoke covers
setup cancellation, credentials, import/deduplication, evidence, private reports,
review cancellation/approval and durable denial after surrender. No live Moltbook
or external publication occurs. GUI and LLM extraction remain open.

Research completion checks also cover Sota-unit-scoped grants for the same Person,
revocation from another SQLite connection during draft confirmation, and a body that stalls after HTTP headers.
Only fixture/local-loopback transports are exercised; live Moltbook schema remains
unverified. The research database is separate from the signed P09 rehearsal database.
