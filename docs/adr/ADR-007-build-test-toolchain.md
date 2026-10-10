# ADR-007 — Build & Test Toolchain

Status: ACCEPTED
Date: 2026-08-27
Level: Implementation Architecture
Formalizes and refines the project's source "SOTA OS — ADR-007 — Build
& Test Toolchain" document (Kotlin, Gradle Wrapper, SQLite, modular
monolith) with the specific test/build tooling that document left open.

## Context

Per ADR-001..006, the technology baseline (Kotlin/JVM+KMP, SQLite,
append-only signed events, deterministic sync merge, JSON/HTTPS,
inert Agent stub) is fixed. The source ADR-007 document further fixes:
modular monolith (not microservices), SQLite as Reference Persistence
Backend #1, Gradle Wrapper as the build tool, and a four-tier test
architecture (T1 Domain / T2 Application / T3 Integration / T4
Architecture-Conformance). It does not pin a specific test framework or
persistence-mapping library. This ADR closes those two gaps.

## Decision

| Concern | Choice | Rationale |
|---|---|---|
| Build tool | **Gradle (Kotlin DSL), wrapper committed to repo** | matches source ADR-007 §8; reproducible build |
| JVM toolchain | **LTS JVM 21**, pinned via `kotlin { jvmToolchain(21) }` | source ADR-007 §9: CI == local, not developer-machine-dependent |
| Test framework | **Kotest** (runner-junit5 + assertions-core + property) | idiomatic Kotlin assertions; property-based testing fits invariant-style tests (e.g. "no Action without valid Authority" holds for *any* generated input) better than plain JUnit |
| Persistence mapping | **SQLDelight** against SQLite | type-safe generated Kotlin from `.sq` files; keeps the KMP door open per ADR-001 (same schema usable from non-JVM targets later) without hand-written JDBC boilerplate |
| Transport/API framework | **Ktor — deferred** | not added as a dependency until a real HTTPS sync/API adapter is implemented (post AC-13). Pulling in a server framework before there is a server to run is scope creep relative to the first vertical slice. |
| Cryptography | **Interface/abstraction only** (`EventSigner` port in `security` module); no concrete algorithm library added yet | ADR-003 already defers the concrete signature scheme to a follow-up ADR-003a; adding a crypto dependency now would front-run that decision |
| Agent runtime | **Registration-only stub** (`agent` module), no execution dependency | ADR-006 |
| Static analysis | **detekt** | lightweight, Kotlin-native, satisfies source ADR-007 §10 "static analysis" requirement without a heavyweight setup |

## Module layout (binding — matches source ADR-007 §4 exactly)

```
sota-os/
├── domain/        pure Kotlin, zero deps on other modules
├── application/    depends on domain only (use-cases, ports)
├── protocol/       depends on domain+application (P01-P10 envelopes)
├── persistence/     depends on domain+application (SQLDelight adapters)
├── security/        depends on domain+application (Rights/Dignity decorator)
├── sync/             depends on domain+application+protocol (P09)
├── agent/            depends on domain+application (registration only)
├── api/               composition root; depends on application+protocol+persistence+security+sync
└── test/               depends on ALL modules (T4 conformance + acceptance)
```

Layering is enforced by Gradle `project(":...")` dependencies, not by
convention: `domain/build.gradle.kts` has no `project()` dependency at
all, which is itself the first architecture-conformance check (a
dependency-graph assertion, see `test/architecture_invariants`).

## Test tiers → module mapping

| Tier | Source ADR-007 name | Lives in | Runs against |
|---|---|---|---|
| T1 | Domain tests | `domain/src/test` | in-memory only, no DB |
| T2 | Application tests | `application/src/test` | fake/in-memory port implementations |
| T3 | Integration tests | `persistence/src/test`, `sync/src/test` | real SQLite file (temp), real (stubbed) transport |
| T4 | Architecture/Conformance | `test/src/test/kotlin/sotaos/test/architecture_invariants` | full stack, per `docs/traceability.md` |
| Acceptance (AC-01..18) | — | `test/src/test/kotlin/sotaos/test/acceptance` | full stack, real SQLite |

## Consequences

- No module may declare a dependency that violates the table above;
  this is checked by `./gradlew verifyModuleLayering`
  (`gradle/module-layering.gradle.kts`, actual production dependency declarations).
- `./gradlew build` is expected to run: compile → detekt → T1 → T2 → T3
  → T4 → acceptance, in that order (source ADR-007 §16 CI pipeline).

## Verification status

The initial source delivery was not compiler-verified. On 2026-09-24,
the persistence adapters, API CLI, security module, and test sources
were compile-verified locally with cached dependencies. On 2026-09-25,
the full local `build :api:installDist --continue --console=plain` run
succeeded on JDK 21 in `/home/zvd/sota-os`, including Detekt and all 27
tests (zero failures, errors, or skipped tests). Interactive CLI smoke
checks also passed, including rejection of an incorrect consent
affirmation and grant/list/revoke persistence. This verifies the local
build; a separate CI run has not been established by this verification.
The subsequent P09 slice extends the passing suite to 55 tests, including
durable two-node sync, real localhost HTTPS and contested-authority
execution rejection. No transport-library or module dependency was added.

## Reversibility

All choices in this ADR are Implementation-level and replaceable per
CORE-22 Protocol over Platform, provided the module layering and the
four-tier test discipline are preserved.

## Production composition refinement — ADR-011

The API composition root now also depends on `sync` to construct P09Runtime.
This wires existing modules; it does not introduce a listener or transport
framework. Integration tests depend on `api` to exercise the actual composition.

## CI runtime maintenance — 2026-09-29

CI uses the explicit ubuntu-24.04 hosted image and Temurin JDK 21. This avoids
the automatic ubuntu-latest major-image migration while retaining hosted image
patch updates; the OS image is not a byte-for-byte immutable environment.

Checkout, setup-java and setup-gradle use verified v5 commits pinned by full SHA.
Their action manifests use Node.js 24, removing the deprecated Node.js 20
action runtime. Major-version comments identify the intended maintenance line;
future upgrades require reviewing and changing the SHA. Official manifests:

- [checkout](https://github.com/actions/checkout/blob/fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09/action.yml)
- [setup-java](https://github.com/actions/setup-java/blob/b6effb05e454b25005698d916606bdc6ffcbf961/action.yml)
- [setup-gradle](https://github.com/gradle/actions/blob/0723195856401067f7a2779048b490ace7a47d7c/setup-gradle/action.yml)

Push and pull-request triggers, read-only contents permission, the 15-minute
timeout and the complete Gradle build remain in place. Gradle setup continues
to supply caching and wrapper validation. The workflow runs compilation, Detekt,
migration verification and all tests; the workflow change is verified by its
GitHub-hosted CI run rather than by adding application tests for YAML text.

## Verification refinement — 2026-10-10

`verifyModuleLayering` is wired into every module's `check`; it verifies the
production graph without compiling application code. The previously referenced
ModuleLayeringTest file did not exist and is superseded by this build-level check.
Test-only dependencies are excluded. T1/T2/T3 placement above remains the target;
the current tests are still concentrated in `test/`. Moving them is separate work.
Gradle schedules tasks by dependencies; the build does not enforce the textual
compile → detekt → T1 → T2 → T3 → T4 ordering claimed above.
