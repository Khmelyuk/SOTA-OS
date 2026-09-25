# ADR-010 - Exit Protocol implementation boundary

Status: ACCEPTED for the voluntary self-exit slice
Date: 2026-09-25

The application exposes typed `ExitRequest`, `ExitSnapshot`,
`ExitPortableData`, `ExitReceipt`, and atomic `ExitRepository.commitExit`.
`ExitService` requires portable export, delegation revocation, and relation
closure before termination. Third-party exit remains fail-closed until the
original AC-18 criteria and an authority-aware invocation contract exist.

Persistence must preserve immutable history and implement the coordinated
transition atomically. The current service is intentionally testable with a
fake port; it does not claim production P10 completion.