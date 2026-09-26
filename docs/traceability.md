# Traceability Matrix — Code ↔ Specification

Per Master Prompt §16. Every row must be answerable; entries left as
`TBD` block Phase 3 completion for that component.

| Code Artifact | Architecture Core | Data Model | Protocol | MVP Req | Test |
|---|---|---|---|---|---|
| `domain/identity/Identity.kt::Person` | CORE-01 Human Primacy | §5 PERSON | P01 | MVP-01 | `architecture_invariants/PersonNotAccountTest` |
| `domain/identity/Identity.kt::Account` | CORE-01 | §7 ACCOUNT | P01 | MVP-01 | same |
| `domain/collective/Collective.kt::Core` | CORE-09/10 Self-Organization / Small Core | §22 CORE | P08 (membership) | MVP-03 | `acceptance/AC02_CoreWithoutAdminTest` |
| `domain/collective/Collective.kt::Sota` | CORE-11 SOTA as Autonomous Unit | §24 SOTA | P08, P11(stub) | — | `architecture_invariants/NonOwnershipTest` |
| `domain/relation/Relation.kt::Trust` | CORE-05 Trust-based Coordination | §19 TRUST | P02 | MVP-04 | `architecture_invariants/TrustNotScoreTest` |
| `domain/relation/Relation.kt::Competence` | CORE-06 Competence-based Authority | §12 COMPETENCE | P03 | MVP-04 | `architecture_invariants/CompetenceEvidenceTest` |
| `domain/relation/Relation.kt::Authority` | CORE-07 Authority is Delegated | §14 AUTHORITY | P04 | MVP-04, AC-05, AC-06 | `architecture_invariants/AuthorityScopedRevocableTest` |
| `domain/agency/Agency.kt::Action` | CORE-03, CORE-17 Decision Traceability | §16 ACTION | P05 | MVP-05, AC-15 | `architecture_invariants/NoActionWithoutAuthorityTest` |
| `security/RightsConstraintDecorator` + `application/ports/RightsConstraint` | CORE-02 Human Dignity Constraint | RIGHT §8-9; Rights Layer §15 | Protocol Architecture §16 | MVP §11, AC-07/15 | Protocol-neutral guard evaluated on state-changing service commands P01-P08; explicit RIGHT and purpose/context/scope-bound CONSENT records now have domain and SQLite persistence; consent is not yet consulted by every action affecting a Person |
| `domain/agency/Agency.kt::Result` | CORE-04 Creation Orientation | §32 RESULT (ACTION != RESULT) | P05 | AC-09 | `unit/ActionResultDistinctTest` |
| `domain/memory/Memory.kt::Event` | CORE-16 Event as Fundamental Fact | §31 EVENT | P06 | MVP-06, AC-14 | `architecture_invariants/EventImmutabilityTest`; ADR-003a Ed25519 and P09 signature-admission tests |
| `domain/memory/Memory.kt::Experience` | CORE-18 Memory is First-Class | §33 EXPERIENCE | P07 | MVP-06 | `unit/EventNotExperienceTest` |
| `domain/memory/Memory.kt::Knowledge` | CORE-19 Knowledge -> Capability | §34 KNOWLEDGE | P07 | MVP-06, AC-11 | `architecture_invariants/NoAICanonicalizationTest` |
| `domain/agency/Agent.kt::Agent` | CORE-21 AI is an Agent, not Principal | Agent/AI §51 | P12 (stub) | MVP §12, AC-16 | `architecture_invariants/NoAgentSelfEscalationTest` |
| `application/protocols/ExitProtocol` + `application/services/ExitService` | CORE-13 Exitability | §51 Exit | P10 | AC-18 | `acceptance/ExitServiceTest`; AC-18 source mapped; durable repository and obligation settlement open |
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
- [ ] P09 production hosting; peer admission/credential provisioning is implemented under ADR-011;
      additional signed entity producers and historical-key verification. See
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
P09 or Phase 3 complete. Production hosting, legacy producer adoption,
historical-key verification and durable P10 remain open.
