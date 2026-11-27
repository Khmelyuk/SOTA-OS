# SOTA OS — Reference Implementation (MVP)

SOTA OS is a collective governance runtime (MVP): a local SQLite-backed CLI node.

Implements the SOTA OS MVP per `docs/adr/` (Implementation Decisions)
and the source specifications (Architecture Core, Data Model, Protocol
Architecture, Security/Trust Architecture, Reference Architecture,
Memory & Knowledge Architecture, Agent/AI Architecture, MVP
Specification, Traceability Matrix, ADR-007).

Run the [two-node Core Loop rehearsal](docs/getting-started.md) with JDK 21:
`./scripts/two-node-core-loop.sh` verifies signed actions and memory, HTTPS recovery and signed exit.
The [TLS rollback rehearsal](docs/p09-tls-rollback.md), `./scripts/p09-tls-rollback.sh`,
checks failed replacement, unchanged journals and recovery using the previous TLS bundle.
The [TLS rollout rehearsal](docs/p09-tls-rollout.md), `./scripts/p09-tls-rollout.sh`,
checks staged replacement under overlapping trust and rejection of retired certificates.
Run `./gradlew :test:migrationAcceptance :test:detekt` for the 43-test historical
SQLite [upgrade matrix](docs/release-upgrade-matrix.md), including interrupted opening and retry.
SQLite opening now checks the persisted schema version before schema changes and
records baseline `1` only after successful initialization ([ADR-030](docs/adr/ADR-030-sqlite-schema-baseline.md)).
The [canonical schema inventory](docs/sqlite-schema-inventory.md) pins all 38 tables,
2 explicit indexes and 51 triggers, including the 41 guards supplied by runtime initialization.

## Status: Phase 3 — signed Core Loop, P09 node, P10 exit and Research CLI

What exists and is source-complete:

- `domain/` — full domain model for the MVP core (Identity, Collective,
  Agency, Relation, Memory, inert Agent stub).
- `application/` — repository ports + services implementing P01, P02-04,
  P05, P06-07 for the vertical slice: **PERSON → CORE → MEMBERSHIP →
  MISSION → AUTHORITY → DECISION → ACTION → RESULT → EVENT →
  EXPERIENCE → KNOWLEDGE** (MVP Spec §15 Core Loop scenario).
- `persistence/Schema.sq` — SQLite schema for the slice (SQLDelight).
- `persistence/SqlDelightRepositories.kt` — SQLDelight-backed adapters for
  all application repository ports; `JsonMapping.kt` handles explicit
  value-object/JSON-column conversion. The persistence module compiles
  against the generated SQLDelight API.
- `api/` — executable local CLI composition root with `help`, `init`,
  `demo`, authentication, consent, and authenticated `exit leave` commands. `demo` persists one
  authorized Core Loop to a local SQLite database. Commands, terminal
  input, database lifetime, and the demo are kept in separate files.
- `security/` — a protocol-neutral RightsConstraint request and checks wired
  into the state-changing P01-P08 service commands. P05 execution preserves
  the order identity, authority, scope, rights check, then persistence; the
  applied policy IDs are included in the resulting Event payload. Ed25519
  event signing, actor-key lifecycle/audit, trusted peer admission and bearer
  credential verification are implemented in the security boundary.
- `sync/` — P09 event exchange, causal deterministic merge without
  last-write-wins, replay protection and `CONTESTED` projections. SQLite
  persists the journal, peer cursors and conflict records atomically.
  Conflicted local authorities cannot be used by P05. `protocol/` provides
  P09 JSON codecs; the HTTPS client and bounded production-runtime pilot host
  are integration-tested ([runbook](docs/p09-https-pilot.md)). See [P09 scope and composition](docs/p09-sync.md).
- Opt-in [signed P05 Actions and Results](docs/p05-signed-actions.md) atomically persist
  `ACTION_EXECUTED` and causally linked `RESULT_RECORDED` facts with canonical signatures
  and P09 verification receipts.
- P05 decisions and execution require a persisted ACTIVE Mission, rechecked before
  execution; decision rights policy receives the actual Mission ID. Historical
  Result reporting remains available after closure ([ADR-022](docs/adr/ADR-022-active-mission-boundary.md)).
- P05 copies the current Mission's CREATION/PROTECTION mode into the executed
  Action and its Event. Signed Result recording checks mode against new signed
  Action evidence, while retaining legacy history ([ADR-024](docs/adr/ADR-024-mission-action-mode.md)).
- P05 Decisions now persist exact Mission terms. Execution requires the current
  ACTIVE Mission to match that snapshot; changed terms or legacy missing snapshots
  require a fresh Decision. New Action Events retain those terms, protected by the
  signed producer ([ADR-027](docs/adr/ADR-027-decision-mission-snapshot.md)).
- Opt-in [signed P07 Experience and Knowledge](docs/p07-signed-memory.md) validate exact
  persisted sources, sign full snapshots with causal links, and atomically record
  creation, human validation and deprecation under ADR-019.
- Ordinary P07 also requires exact persisted sources and current Knowledge states;
  source checks, rights evaluation and writes share one transaction under
  [ADR-023](docs/adr/ADR-023-persisted-memory-boundary.md). Local unsigned facts remain
  supported; signed history and general validation mandates are separate boundaries.
- Ordinary P07 atomically records local lifecycle audit Events with complete
  snapshots, the invoking actor and exact deprecation reason. Signed P07 uses the
  same transaction hook with its existing signed facts, without duplicate local
  Events ([ADR-025](docs/adr/ADR-025-local-memory-audit.md)).
- P07 `reviseKnowledge` creates a new candidate with the next version and an
  immutable predecessor link. Both runtimes retain the previous version and status,
  reject stale/forked revisions and record the exact reason and source; repository
  saves preserve version content ([ADR-026](docs/adr/ADR-026-knowledge-version-lineage.md)).
- P04 delegation records and validates the complete ancestor chain, with
  atomic cascade revocation under [ADR-013](docs/adr/ADR-013-delegation-lineage.md).
  New grants reject already expired validity windows. P05 decision creation
  checks that the acting subject holds the referenced authority.
- P08 [membership admission](docs/adr/ADR-020-persisted-membership-admission.md)
  persists invitations issued by the target collective and requires exact current
  records for acceptance, leave and authority-checked removal. Core creation and
  founding membership are atomic. The [command authorization audit](docs/command-authorization.md)
  distinguishes these checks from still-open collective mandates and host authentication.
- Opt-in [authenticated Core admission](docs/p08-authenticated-admission.md) verifies
  fresh local proofs for the representative and invitee. An explicit Core/unit/recipient
  mandate and its current lineage are checked before invitation and again at acceptance;
  membership and human audit commit together. This consumes existing mandates and
  does not authorize initial root issuance or general collective impersonation.
- `test/` — executable Kotest tests: the full Core Loop scenario
  (`acceptance/CoreLoopVerticalSliceTest`) plus architecture-invariant
  tests (no action without authority, event append-only, no AI
  knowledge canonicalization, Person≠Account, no self-escalation in
  delegation) — running against in-memory fakes. A SQLite integration
  scenario also verifies that Core Loop records survive closing and
  reopening the database.

- P09 `sync check` reports server certificate fingerprints, UTC validity, remaining
  time and an advisory 30-day renewal warning; expired/future certificates still fail
  ([ADR-028](docs/adr/ADR-028-p09-certificate-diagnostics.md)).
- P09 `sync compare --config CURRENT --candidate STAGED` compares locally valid
  TLS bundles with unchanged node settings and shared or independent passwords, showing server-leaf
  and client-trust fingerprint changes ([ADR-029](docs/adr/ADR-029-p09-tls-bundle-comparison.md)).

What is **not yet done**:

- `persistence/` has Core Loop and two-node sync integration coverage,
  including restart, additive sync-schema migration and rollback.
  Two declared-schema profiles and two captured runtime profiles now verify
  row preservation, schema contracts, current service behavior and retry after a
  failed upgrade. Six additional tests preserve runtime guards, signed receipts and
  key/peer history; seven more cover completed/pending P10 exits and retained obligations.
  Twelve opening-interruption cases now cover all four profiles at two completed-statement
  boundaries and before version marking, with comparison against uninterrupted upgrade
  after retry/reopen. Four further cases verify unknown-version byte preservation and
  legacy upgrade through CLI `init`; seven API tests cover fresh and rejected opening paths.
  See [migration acceptance](docs/persistence-migrations.md); broader
  repository and release-migration coverage is still open.
- P09 public hosting operations, automatic historical attestations, producer adoption and additional
  entity assertion producers remain open. Credential authentication, trusted
  peer admission, event signatures, durable provisioning governance and
  `api.sync.P09Runtime` are implemented under ADR-011. Explicit HTTPS pilot hosting
  and CLI sync commands are implemented under [ADR-015](docs/adr/ADR-015-p09-https-pilot.md).
  [Background sync](docs/p09-background-sync.md) adds combined hosting, capped retries
  and coordinated shutdown under ADR-017. The
  general P01-P10 envelope/dispatch layer and `agent/` remain incomplete.
- The current rule set enforces the known no-Agent-command MVP limit.
  State-changing service commands P01-P08 now carry ProtocolInvocation
  and pass through the constraint. Person bootstrap creation remains a
  deliberate unguarded setup path. RIGHT/CONSENT records are persisted,
  and P05 execution checks affected People's consent. Broader
  command-specific authority checks remain open beyond the existing P05
  checks and P08 boundaries recorded in ADR-008/020. See the
  [command-to-check-to-test mapping](docs/command-authorization.md).
- P10 general contract settlement and third-party exit remain open.
  Recorded delegation chains now support cascade revocation and full ancestor
  checks; see [ADR-013](docs/adr/ADR-013-delegation-lineage.md). Durable local self-exit, governed export, retained obligations
  and CLI delivery are implemented; see [P10 evidence](docs/p10-exit.md).

CI is defined in `.github/workflows/ci.yml` and runs the full JDK 21 build,
Detekt, migration verification and test suite on pushes and pull requests.
The runner is Ubuntu 24.04; Node.js 24 Actions are pinned by commit SHA
([toolchain policy](docs/adr/ADR-007-build-test-toolchain.md)).

[GitHub CI](https://github.com/Khmelyuk/SOTA-OS/actions/workflows/ci.yml) is the
current build/test status for each commit.
Focused verification on 2026-11-27 passed **43 tests** (zero failures, errors or skips)
and Detekt, including seven canonical-catalog checks. Independent recapture matched
the pinned inventory and all 43 persistence source fingerprints. Production code is
unchanged in this increment; the prior full-suite verification follows.
Local verification on 2026-11-26: the full JDK 21 build, Detekt and module-layering
checks passed with **409 tests** (zero failures, errors or skips), including the TLS
rollout/rollback and historical P09/P10 scenarios. After the final catalog-inspection
refinement and one additional regression, all **36 migration tests and 30 API tests**
passed again, including seven shared CLI-opening cases. The installed CLI separately
passed fresh `init`, reopen, baseline marking and newer-version rejection with
unchanged database bytes. SQLDelight migration verification is included in the build;
numbered `.sqm` migrations remain future work. The CLI distribution
and two-node HTTPS rehearsal passed with ten matching signed records and
checkpoints `(10, 10)`. The separate TLS rollback rehearsal passed with eight matching
signed records and checkpoints `(8, 8)` after trust failures in both directions.
The staged TLS rollout passed with 24 matching signed records and checkpoints `(24, 24)`,
including mixed old/new peers and explicit rejection after old trust was retired.
During the Decision/Mission snapshot increment,
ordinary CLI `init` and `demo` passed on a new
temporary SQLite database with Decision/Mission snapshots, local P07 audit and immutable Knowledge versions enabled;
repeated `init` reopened that database successfully. The historical migration profiles retain
original governance, memory, credential and journal rows without inventing Mission snapshots
or signed verification evidence.
The signed-memory recovery test exchanged seven facts including revision and separate validation.
Research CLI smoke passed during the
persisted-membership increment.
P09 status, signed P10 and credential lifecycle smoke scenarios passed during
integration on 2026-11-17 and remain part of CI. The published TLS rollback
baseline also passed [GitHub CI](https://github.com/Khmelyuk/SOTA-OS/actions/runs/45468418704).
This verifies the integrated local tree; the CI link
above records the result for each published commit.

The full build includes Detekt; no checks were excluded. With JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build --continue --console=plain
```

The suite covers Core Loop, consent, authentication,
architecture invariants, P09 merge/persistence/recovery/validation/atomicity/
HTTPS, actor-key lifecycle, peer admission, authenticated endpoint handling
and durable P10 exit. Fourteen SQLite-backed P10 tests replace the three former
fake-port tests and cover restart, scoped cleanup, rollback, governed export,
retained obligations, private file delivery and migration. Six additional signed-P10
tests cover atomic journaling, two-node recovery, key/policy denials and mode
continuity. Eight historical-signature tests cover retirement, exact-record
replay, backdating rejection, receipt rollback, migration and producer verification.
Sixteen delegation tests cover scope/time ceilings, recorded ancestry, cascades,
Core isolation, restart, rollback, audit, migration and consuming services.
The 24 original production-P09 tests
cover registry governance, signed relay/sharing policy, recovery and local autonomy.

The Detekt cleanup extracts smaller CLI/service functions and separates
JSON mappings by responsibility. The existing Detekt configuration is
unchanged. Three documented `LongParameterList` suppressions preserve
the existing Authority/Consent grant contracts and the explicit
MissionActionService dependency-injection contract.

CLI smoke checks passed on a temporary SQLite database using the built
distribution: help, initialization, Core Loop demo, local login creation
and authentication, consent grant/list/revoke, and persisted revoked
status. An incorrect affirmation was also rejected through the CLI;
listing confirmed that no consent had been saved. P10 CLI smoke also verified
local authentication, rejected exit confirmation, selected-Core termination,
private archive permissions, matching SHA-256 and retained credentials.

Node settings can be loaded with `sync run --config /path/node.conf`; see the
[configuration format](docs/p09-background-sync.md#node-configuration-file).

TLS and peer credential updates use [staged material validation and governed rotation](docs/p09-material-rotation.md).

## Current gaps and next steps

The current slice now models RIGHT and CONSENT records and
persists them in SQLite; `ConsentService` supports explicit grant, local
revocation, and exact purpose/context/scope evaluation. The RightsConstraint
is a denial boundary, not a substitute for authorization and consent checks.
A decision now records its purpose and affected People; execution requires
the persisted, unchanged decision and checks each affected Person's consent
for the exact purpose, context, action, and person resource. The CLI displays
the terms and records a grant only after the exact phrase `НАДАЮ ЗГОДУ`; it
also supports authenticated listing and revocation. Local authentication uses
a passphrase verifier; initial account creation/linking is a trusted local
bootstrap step and does not prove legal identity. The provider-neutral
authentication port and per-Sota provider allowlist are described in
[ADR-009](docs/adr/ADR-009-provider-based-authentication.md). Only the local
provider is implemented; Google/OIDC, Diia, and qualified-signature adapters
remain future work. The current verification suite passes as described above.
The next increments, updated after the canonical schema inventory on 2026-11-27, are recorded
in the [development roadmap](docs/roadmap.md). Delegation chains, CI, peer
admission, signatures and HTTPS hosting are already implemented. The next independent
engineering increment is SQLDelight's migration-verification baseline and the ordering
contract for numbered migrations with runtime guards retained. The exact version-one
catalog and generated/runtime delta are now pinned. Direct `.sq` placement of all guards
was rejected by the configured generator for `OLD`/`NEW` references in trigger conditions;
the existing executable definitions remain unchanged. Persisted baseline `1` and the
shared opening boundary are implemented under ADR-030.
The [upgrade matrix](docs/release-upgrade-matrix.md)
now defines four verified commit/profile sources and preservation/failure/retry criteria;
named release-to-release support remains open. Successful overlapping-trust
replacement and retirement now have a [reproducible rehearsal](docs/p09-tls-rollout.md).
Failed replacement and previous-bundle
recovery now have a [reproducible rehearsal](docs/p09-tls-rollback.md). Independent
candidate password sources, bundle comparison and operator recovery checks are documented
under ADR-029; local certificate-expiry diagnostics are implemented under ADR-028. Four historical profiles
now cover reopen, runtime guards, signed provisioning history and populated P10 exits.
Further functional priorities are:

1. Extend command-specific authorization and source conformance: explicit
   initial collective mandate/root eligibility and further command conformance.
   Delegated Core invitations now have a fresh-proof host under ADR-021;
   broader collective representation, P05 participation/numeric revision and mode-change governance, and
   P07 validation mandates and general branch/conflict governance remain open.
   Initial Core mandate approval/quorum needs
   an explicit owner policy; independent source review can continue meanwhile.
   The [authorization audit](docs/command-authorization.md) records the next review slices.
2. Extend node operations with automated certificate renewal and broader configuration lifecycle support, additional signed producer adoption and automatic historical
   attestations using the [source-to-test mapping](docs/ac-p09-mapping.md).
3. Extend P10 beyond the typed local Trust/Agreement inventory to multiparty consent
   and additional settlement policies ([ADR-014](docs/adr/ADR-014-relation-inventory.md));
   recorded delegation cascades, the durable local AC-18 slice and opt-in
   SignedP10Runtime producer are implemented. The CLI supports explicit signed exit with a provisioned Ed25519 key; unsigned local mode remains the default.

Run the CLI with an explicit database path when needed:

```bash
./gradlew :api:run --args='init --db /tmp/sota-os.db'
./gradlew :api:run --args='demo --db /tmp/sota-os.db'
./gradlew :api:run --args='auth create --handle local-handle --db /tmp/sota-os.db'
./gradlew :api:run --args='consent grant --handle local-handle --recipient PERSON_ID --purpose "Purpose text" --context DOMAIN --action ACTION --db /tmp/sota-os.db'
./gradlew :api:run --args='consent list --handle local-handle --db /tmp/sota-os.db'
./gradlew :api:run --args='consent revoke --handle local-handle --id CONSENT_ID --db /tmp/sota-os.db'
```

For local passphrase rotation and revocation, see the
[P01 credential lifecycle guide](docs/p01-credential-lifecycle.md).

For an existing Person, a trusted local operator can bootstrap the credential
with `auth enroll --person PERSON_ID --handle HANDLE`. Person IDs and local
handles are never accepted as authentication proof. An authentication
provider must be enabled explicitly for the Sota unit; the CLI currently
enables only `local-passphrase`.

## Repository structure

See `docs/adr/ADR-007-build-test-toolchain.md` for the binding module
layout and layering rule.

## Principle

> Increase capability. Preserve autonomy.
> Implement the architecture. Do not silently redesign it.

## Production peer admission

The P09 composition now uses SQLite-backed peer trust and credential verifiers,
authorized enroll/rotate/revoke with operator/authority audit, and strict signed
origin/provenance/sharing checks. See
[ADR-011](docs/adr/ADR-011-production-peer-admission.md) for setup boundaries,
canonical signed-record production and limits. The runtime rejects unsigned or
legacy-hash events instead of implicitly upgrading immutable history.
[AC-12/13/17 mapping](docs/ac-p09-mapping.md) now cites the original MVP PDF,
its exact criteria and concrete tests; deployment and legacy-producer gaps remain.

Historical signature handling now preserves exact locally verified records after
key rotation/revocation through atomic append-only receipts. Unknown old-key
records remain denied by default. [ADR-012](docs/adr/ADR-012-historical-signature-evidence.md)
documents local receipts; [ADR-016](docs/adr/ADR-016-historical-record-approval.md)
adds independently governed approval of one exact historical record on a new node.
It preserves current keys and peer policy, with immutable audit and revocation.
See the [historical recovery guide](docs/p09-historical-recovery.md).

## SOTA Research Agent

Local human-invoked candidate knowledge, public discussion ingestion, provenance,
Ukrainian reports and reviewed drafts are available in the existing `agent` module.
The local `research` CLI is enabled under ADR-018. See [Research Agent README](agent/README.md)
for first-run commands, authorization, limits and tests.
External publication is disabled; A21 remains draft.
