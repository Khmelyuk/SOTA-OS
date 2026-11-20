# ADR-022 — Persisted active Mission at decision and execution

Date: 2026-11-20
Status: Accepted for the current P05 slice

## Source and reproduced gap

Protocol Architecture v0.1 §8 (PDF pp.8–9) defines the traceable
Intent → Mission → Decision → Action → Result chain. Data Model v0.1 §27
(PDF pp.16–17) gives Mission an owner, intent and status. Security & Trust
Architecture v0.1 §14 (PDF pp.9–10) includes state in authorization checks.
These are paraphrased requirements; they do not define a full mission admission,
participant governance or lifecycle protocol.

The current MissionActionService stored the Mission ID in Decision.intentRef,
but never loaded that Mission in decide/execute. A valid authority could therefore
create a decision for an unknown Mission. Execution could proceed after the
referenced Mission disappeared or became inactive. The decision rights request
also omitted the actual Mission ID when the authority had a broader context.
Five new regressions reproduced these gaps before the implementation changed.

Source fingerprints:

- Protocol Architecture: SHA-256 `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.
- Data Model: SHA-256 `6a8972ed7b86446cda4f035cf30a61fd9ccdf6ab1a108d1c1478b9dce0d4eee4`.
- Security & Trust Architecture: SHA-256 `3edb5eafa44ae4283f53c18f2ad7e8419ca224a215357768f0f3f51e00875bd1`.

## Decision

Both decide and execute load the referenced Mission from the repository inside
the existing authority transaction. It must exist and be ACTIVE before policy
evaluation or a Decision/Action/Event write. This is an explicit bounded lifecycle
rule: formMission currently creates ACTIVE missions, and this increment does not
define execution semantics for any other generic LifecycleState value.

Execution rechecks the current Mission independently of decision creation and
after SQLite reopen. A previously accepted Decision is not permission to continue
an inactive Mission. Missing or inactive records raise ActionNotAuthorizedException;
they are not fabricated, silently restored or substituted from invocation context.

The decision RightsConstraintRequest carries the persisted Mission ID. Authority
context, holder, scope, lineage, validity and affected-Person consent checks remain
enforced. SignedP05Runtime delegates to the same service and retains atomic
Action/Event/journal/receipt writes. All injected SQLite repositories must share a
store and its local-access gate, as required by the existing composition contract.

## Compatibility and remaining boundaries

Existing callers/fixtures that only invented a Mission ID must create or load a
real active Mission first. Old decisions referencing missing/inactive Missions
remain stored history but cannot execute. Reopening a Mission through a future
authorized lifecycle operation may make its saved Decision eligible again; all
other execution checks still apply.

Recording a Result reports an already executed Action. It continues to require
the exact stored Action, correct author and rights checks, but does not newly
require the Mission or original execution authority to remain active. Signed
Result recording retains its own verified-source/current-key requirements.

This increment adds no schema migration, Mission state-changing command, remote
projection, ownership/participation rule, or automatic authority from membership.
Mission version/snapshot binding and mode propagation remain separate conformance
questions; a reference to an active Mission is not proof of full governance.

## Evidence

MissionStateBoundaryTest contains six SQLite-backed tests covering unknown
Missions, every non-ACTIVE state, ordinary/signed execution after reopen, missing
Mission references, policy Mission ID and Result reporting after closure/revocation.
Existing authority-owner, scope, consent and revocation fixtures now contain real
active Missions so their checks cannot pass accidentally through a missing-Mission
rejection. The unknown-authority regression also persists its Decision and matches
the intended authority-denial path.
