# SOTA OS — Reference Implementation (MVP)

Implements the SOTA OS MVP per `docs/adr/` (Implementation Decisions)
and the source specifications (Architecture Core, Data Model, Protocol
Architecture, Security/Trust Architecture, Reference Architecture,
Memory & Knowledge Architecture, Agent/AI Architecture, MVP
Specification, Traceability Matrix, ADR-007).

## Status: Phase 3 — Core Loop, local CLI and P09 reconciliation slice

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
- Opt-in [signed P05 actions](docs/p05-signed-actions.md) atomically persist
  `ACTION_EXECUTED` facts with canonical signatures and P09 verification receipts.
- `test/` — executable Kotest tests: the full Core Loop scenario
  (`acceptance/CoreLoopVerticalSliceTest`) plus architecture-invariant
  tests (no action without authority, event append-only, no AI
  knowledge canonicalization, Person≠Account, no self-escalation in
  delegation) — running against in-memory fakes. A SQLite integration
  scenario also verifies that Core Loop records survive closing and
  reopening the database.

What is **not yet done**:

- `persistence/` has Core Loop and two-node sync integration coverage,
  including restart, additive sync-schema migration and rollback.
  Broader repository and migration coverage is still open.
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
  checks and the scoped P08 removal check recorded in ADR-008.
- P10 general contract settlement and third-party exit remain open.
  Recorded delegation chains now support cascade revocation and full ancestor
  checks; see [ADR-013](docs/adr/ADR-013-delegation-lineage.md). Durable local self-exit, governed export, retained obligations
  and CLI delivery are implemented; see [P10 evidence](docs/p10-exit.md).

CI is defined in `.github/workflows/ci.yml` and runs the full JDK 21 build,
Detekt, migration verification and test suite on pushes and pull requests.
The runner is Ubuntu 24.04; Node.js 24 Actions are pinned by commit SHA
([toolchain policy](docs/adr/ADR-007-build-test-toolchain.md)).

Verification on 2026-09-29 in `/home/khmelyuk/SOTA-OS-local`:
**BUILD + TEST PASSED**.
The full build includes Detekt; no checks were excluded. With JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build --continue --console=plain
```

The current suite has **209 test cases**, all passing with zero failures,
errors, or skipped tests. It covers Core Loop, consent, authentication,
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
Follow with:

1. Extend node operations with automated certificate renewal and broader configuration lifecycle support, additional signed producer adoption and automatic historical
   attestations using the [source-to-test mapping](docs/ac-p09-mapping.md).
2. Extend P10 beyond the typed local Trust/Agreement inventory to multiparty consent
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
