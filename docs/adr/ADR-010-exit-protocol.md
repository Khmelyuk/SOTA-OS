# ADR-010 - Exit Protocol implementation boundary

Status: ACCEPTED for the voluntary self-exit slice
Date: 2026-09-25

The application exposes typed `ExitRequest`, `ExitSnapshot`,
`ExitPortableData`, `ExitReceipt`, and atomic `ExitRepository.commitExit`.
`ExitService` requires portable export, delegation revocation, and relation
closure before termination. Third-party exit remains fail-closed until an authority-aware invocation
contract exists. The original AC-18 criterion is now mapped in docs/ac-p09-mapping.md.

Persistence must preserve immutable history and implement the coordinated
transition atomically. The current service is intentionally testable with a
fake port; it does not claim production P10 completion.
## Source clarification — 2026-09-26

MVP Specification v0.1 §16 (PDF page 18) defines AC-18 as “Користувач може
вийти з Core”. Protocol Architecture v0.1 §13 (PDF page 13) additionally
requires authority verification, delegation revocation, relation closure,
obligation settlement, allowed-data export and termination. The current port
needs an explicit target-Core contract and durable settlement/export behavior
before this full flow can be claimed complete.
