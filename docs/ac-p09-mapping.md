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
| AC-13 | Синхронізувати після відновлення зв'язку | `ProductionRoundTripTest`: signed records recorded offline, failed transport, then authenticated exchange between two SQLite production roots; `SyncRecoveryTest`: lost acknowledgements, retries, pagination and restart; `HttpsProductionPilotTest`: production HTTPS, both stores reopened after lost reply; `P09HttpsBoundaryTest` / `P09TlsTest`: input limits and TLS trust | Covered for signed profile records; general production rollout remains PARTIAL because legacy unsigned/Core Loop hash producers cannot pass strict admission and public-hosting operations remain open; an explicit bounded HTTPS pilot listener is supplied under ADR-015 |
| AC-17 | Втрата центрального сервісу не знищує локальний Core | `LocalAutonomyAcceptanceTest`: unavailable service throws IOException; Core and active membership survive SQLite close/reopen; creating another Core remains possible while service is unavailable | Direct automated local-autonomy evidence; independent of conflict detection |
| AC-18 | Користувач може вийти з Core | `ExitServiceTest` and `ExitExportTest`: durable self-exit, isolation, restart, rollback and governed export | Local voluntary P10 implemented; typed participation and recorded delegation cascades implemented; broader contract policies open; see [P10 evidence](p10-exit.md) |

## P09 implementation trace

- Offline persistence: local domain repositories and append-only event journal.
- Recovery: SyncService checks acknowledged cursors and retries without duplicates.
- Trust: production peer registry, per-direction origin/context/entity allowlists,
  hashed credentials and live key lookup; no network call is needed for local work.
- Integrity: signed canonical records bind content and provenance to origin/parents/assertion.
- Conflict retention: SyncConflictPreservationTest and SyncAuthorityBoundaryTest
  verify ADR-004/P09 behavior; **they are not a substitute for AC-17**.
- Public server operations, all entity producers and automatic historical attestations
  remain deployment/coverage gaps. Exact locally governed historical recovery is
  implemented under ADR-016; it does not add or rewrite source criteria.

## P10 scope from the original protocol

Protocol Architecture §13 requires REQUEST EXIT → VERIFY AUTHORITY → REVOKE
ACTIVE DELEGATIONS → CLOSE RELATIONS → SETTLE OBLIGATIONS → EXPORT ALLOWED DATA
→ TERMINATE PARTICIPATION. Exit must not depend on central-server permission
except where relationship terms lawfully constrain a particular action.

The durable local self-exit implementation covers the ordered stages in SQLite.
See [P10 evidence](p10-exit.md) and [ADR-010](adr/ADR-010-exit-protocol.md).
Unfulfilled obligations are retained, not silently discharged. `RelationInventoryTest`
adds typed terms, obligation links, atomic import and closure evidence under ADR-014. General contract
settlement remains outside this slice. Recorded downstream delegation chains
now cascade under ADR-013, with Core isolation and rollback tested. Opt-in
signed P10 production is covered by SignedExitTest, including P09 recovery after
a lost acknowledgement and restart; see the P10 guide. Third-party exit is denied.

## Historical P09 recovery evidence — 2026-09-29

HistoricalSignatureTest extends AC-13 evidence: exact locally verified records
remain usable after actor-key rotation/revocation and restart. Receipts commit
atomically with the journal. Unknown old-key records cannot be accepted by
backdating. This does not claim recovery on a node that has never verified the
record; see [ADR-012](adr/ADR-012-historical-signature-evidence.md).

## Production HTTPS pilot — 2026-09-30

ADR-015 and the [pilot runbook](p09-https-pilot.md) connect AC-12/13/17 to signed
records created independently, real TLS transport, durable peer/key configuration,
lost-response recovery and both stores reopening. CLI `sync serve` and `sync once`
require explicit node/actor configuration and provisioned stores. No central server
is needed. This extends transport evidence without claiming public deployment,
automatic scheduling or authorization of unseen historical old-key records.

## Governed historical import evidence — 2026-09-30

HistoricalApprovalHttpsTest extends AC-13: a receiving node rejects unknown old-key
history, then recovers over HTTPS after an independently authorized local approval
and restart. HistoricalApprovalTest verifies exact-record scope, no active-key
replacement, current policy and governance. HistoricalApprovalDurabilityTest verifies
transaction rollback, revocation recheck and migration preserving existing receipts.

This is explicit local acceptance based on independently reviewed evidence, not
proof of signature creation time or transferable peer testimony. Default rejection
of unknown old-key records remains. See [ADR-016](adr/ADR-016-historical-record-approval.md).

## Background recovery evidence — 2026-09-30

P09NodeHostTest adds automatic offline recovery and lost-response restart evidence
for AC-13, including journals larger than one page and simultaneous HTTPS hosts.
Local signed recording remains possible while hosts run (AC-12/17 local autonomy).
SyncConcurrencyTest preserves a newer incoming checkpoint when an outbound reply
is stale. ADR-017 does not replace source criteria or claim public deployment.
