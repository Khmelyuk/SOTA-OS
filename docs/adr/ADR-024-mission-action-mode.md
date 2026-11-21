# ADR-024 — Mission mode in executed P05 Actions

Date: 2026-11-21
Status: Accepted for the bounded local MVP slice

## Sources and gap

Data Model v0.1 §16 (PDF p.11) includes mode in Action; §27 (pp.16–17)
includes it in Mission; §50 (p.28) defines CREATION and PROTECTION as modes
of one activity cycle. Protocol Architecture v0.1 §21 (pp.18–19) requires
the same base protocols to operate in both modes. These are paraphrases;
they do not specify Mission version approval or mode-specific protection policies.

Source fingerprints:

- Data Model: SHA-256 `6a8972ed7b86446cda4f035cf30a61fd9ccdf6ab1a108d1c1478b9dce0d4eee4`.
- Protocol Architecture: SHA-256 `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.

MissionActionService previously discarded Mission.mode and always created an
Action with CREATION. The execution Event did not include mode, so even signed
history could not attest that field of the persisted Action.

## Decision

Execution uses the mode of the persisted ACTIVE Mission already loaded inside
the ADR-022 transaction. It copies that value into Action.mode and the new
ACTION_EXECUTED payload field `mode`, using the enum name. Both ordinary and
signed P05 share this path. The canonical P09 signature binds the new payload;
ordinary local events remain unsigned under their existing contract.

The mode is taken at execution time. Decision currently stores a Mission ID,
not a version or mode snapshot. A trusted change to Mission.mode before execution
therefore affects the new Action. This is an explicit bounded behavior, not an
approval protocol for changing mission terms. No public mode-change command is
added. Once recorded, an Action's mode is historical and does not follow later
Mission changes. Result recording continues to use that exact persisted Action.

Signed Result recording also compares the Action's mode with its execution
Event when the Event contains `mode`. A differing or malformed value is rejected
before Result/Event/journal/receipt writes, even if the Action row was replaced
directly. The existing source signature and local verification evidence checks
still apply. RESULT_RECORDED keeps its existing payload and causal Action parent.

## Compatibility and limits

No schema migration or event rewrite is needed: Mission and Action already have
mode columns. Existing signed Action events without a mode field remain usable
under the previous Result contract. Their signatures do not prove Action.mode;
no mode value is backfilled or inferred from the current Mission. Existing Actions
incorrectly recorded as CREATION are also retained as history, not silently fixed.

CREATION/PROTECTION do not grant authority, bypass consent or weaken rights checks.
The existing authority lineage, scope, validity, persisted Decision and active
Mission checks remain mandatory. Mode-specific policy inputs, mission participation,
version/snapshot binding and governance for mode changes remain separate reviews.
Receiving a signed mode fact through P09 does not execute a remote Action.

## Evidence

MissionModeTest covers both modes and both execution paths, SQLite reopen,
execution-time Mission reload, historical Action/Result continuity, signed mode
tampering, direct Action-row mismatch, legacy signed records without mode, and
atomic rollback/retry for PROTECTION. SignedActionTest now runs its lost-response,
restart and duplicate-free two-node exchange with a PROTECTION Action.
Before the fix, four new regressions and that updated exchange test failed;
the legacy compatibility case passed.
