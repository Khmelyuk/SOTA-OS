# AC-12/13/17 source-to-test mapping

Source reviewed on 2026-09-26: **SOTA OS — MVP Specification v0.1**,
§16 “MVP Acceptance Criteria”, PDF page 18, file `08 MVP Specification v0.1.pdf`.
The local source set is `/home/khmelyuk/Документи/SOTA OS/PDF/`.
SHA-256: `fa1e825a11987efc2f8bc5b2e3ee24d775fdacb480e3167bf6154fca3cef078a`.

Protocol context: **Protocol Architecture v0.1**, §12 P09, PDF pages 12–13;
§13 P10, PDF page 13, file `03 Protocol Architecture v0.1.pdf`.
SHA-256: `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.
The PDFs remain external source material; exact criterion text and source
fingerprints are preserved here so the mapping is reviewable in Git.

The source table's PASS column specifies expected acceptance outcomes, not
observed implementation test results. The evidence and limits below describe
what this repository actually verifies.

| ID | Exact source wording | Executable evidence | Scope and remaining gap |
|---|---|---|---|
| AC-12 | Працювати offline | `SqlDelightCoreLoopIntegrationTest`: full authorized Core Loop without a network service; `LocalAutonomyAcceptanceTest`: local Core creation before/during outage and after restart | Automated coverage of the local MVP slice; no network authorization dependency |
| AC-13 | Синхронізувати після відновлення зв'язку | `ProductionRoundTripTest`: signed records recorded offline, failed transport, then authenticated exchange between two SQLite production roots; `SyncRecoveryTest`: lost acknowledgements, retries, pagination and restart; `HttpsSyncTransportTest`: real HTTPS adapter | Covered for signed profile records; general production rollout remains PARTIAL because legacy unsigned/Core Loop hash producers cannot pass strict admission and no hosted listener is supplied |
| AC-17 | Втрата центрального сервісу не знищує локальний Core | `LocalAutonomyAcceptanceTest`: unavailable service throws IOException; Core and active membership survive SQLite close/reopen; creating another Core remains possible while service is unavailable | Direct automated local-autonomy evidence; independent of conflict detection |
| AC-18 | Користувач може вийти з Core | `CollectiveService.leave` and existing voluntary `ExitServiceTest` are partial evidence | Full durable P10 flow remains OPEN; see below |

## P09 implementation trace

- Offline persistence: local domain repositories and append-only event journal.
- Recovery: SyncService checks acknowledged cursors and retries without duplicates.
- Trust: production peer registry, per-direction origin/context/entity allowlists,
  hashed credentials and live key lookup; no network call is needed for local work.
- Integrity: signed canonical records bind content and provenance to origin/parents/assertion.
- Conflict retention: SyncConflictPreservationTest and SyncAuthorityBoundaryTest
  verify ADR-004/P09 behavior; **they are not a substitute for AC-17**.
- Actual server deployment, all entity producers and historical signature handling
  remain deployment/coverage gaps, not reasons to fabricate source criteria.

## P10 scope from the original protocol

Protocol Architecture §13 requires REQUEST EXIT → VERIFY AUTHORITY → REVOKE
ACTIVE DELEGATIONS → CLOSE RELATIONS → SETTLE OBLIGATIONS → EXPORT ALLOWED DATA
→ TERMINATE PARTICIPATION. Exit must not depend on central-server permission
except where relationship terms lawfully constrain a particular action.

The current voluntary ExitService provides ordering checks through a fake-tested
port. It lacks durable ExitRepository transitions, an explicit target-Core exit
contract, obligation settlement and a production allowed-data export policy.
Third-party exit is denied. These are concrete AC-18/P10 follow-up gaps; the
criterion source is now available and is no longer the blocker.
