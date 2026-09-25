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
  `demo`, authentication, and consent commands. `demo` persists one
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
  P09 JSON codecs; an HTTPS client and a server-neutral handler are
  integration-tested. See [P09 scope and composition](docs/p09-sync.md).
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
- P09 production hosting, peer credentials/admission policy and additional
  entity assertion producers remain open. No network listener or CLI sync
  command is enabled. The general P01-P10 envelope/dispatch layer and
  `agent/` remain incomplete; see `docs/traceability.md`. Ktor remains
  deferred until an API server is introduced, per ADR-007.
- The current rule set enforces the known no-Agent-command MVP limit.
  State-changing service commands P01-P08 now carry ProtocolInvocation
  and pass through the constraint. Person bootstrap creation remains a
  deliberate unguarded setup path. RIGHT/CONSENT records are persisted,
  and P05 execution checks affected People's consent. Broader
  command-specific authority checks remain open beyond the existing P05
  checks and the scoped P08 removal check recorded in ADR-008.
- Durable P10 `ExitRepository` transitions and authority-aware third-party
  exit remain open. The voluntary self-exit service slice is implemented.

CI is defined in `.github/workflows/ci.yml` and runs the full JDK 21 build,
Detekt, migration verification and test suite on pushes and pull requests.

Verification on 2026-09-25 in `/home/zvd/sota-os`: **BUILD + TEST PASSED**.
The full build includes Detekt; no checks were excluded. With JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build --continue --console=plain
```

All **55 tests passed**, with zero failures, errors, or skipped tests:
the previous 27 Core Loop, consent, authentication and architecture tests,
plus 28 P09 tests covering merge, persistence/recovery, validation,
atomicity, HTTPS and the contested-authority execution boundary.

The Detekt cleanup extracts smaller CLI/service functions and separates
JSON mappings by responsibility. The existing Detekt configuration is
unchanged. Three documented `LongParameterList` suppressions preserve
the existing Authority/Consent grant contracts and the explicit
MissionActionService dependency-injection contract.

CLI smoke checks passed on a temporary SQLite database using the built
distribution: help, initialization, Core Loop demo, local login creation
and authentication, consent grant/list/revoke, and persisted revoked
status. An incorrect affirmation was also rejected through the CLI;
listing confirmed that no consent had been saved.

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

1. Map the original AC-12/AC-13/AC-17 text to the implemented P09 slice;
   those source criteria are not included in this repository. Complete
   production peer admission/hosting and further entity producers as
   required by that mapping.
2. Implement the `ExitProtocol` service (P10) for AC-18.

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
