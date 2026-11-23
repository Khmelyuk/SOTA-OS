# ADR-027 — Bind execution to the Mission terms recorded with its Decision

Date: 2026-11-23
Status: Accepted for the bounded local MVP slice

## Sources and reproduced gap

Data Model v0.1 §15 (PDF pp.10–11) requires Decision traceability; §16 (p.11)
includes mode in Action, and §27 (pp.16–17) describes Mission intent, owner,
objectives, participants, mode and status. Protocol Architecture v0.1 §8
(pp.8–9) requires preserving the Mission → Decision → Action causal chain and
what was decided. §21 (pp.18–19) applies the same base protocols in both modes.
These are paraphrases, not a specified numerical Mission revision protocol.

Source SHA-256 fingerprints:

- Data Model: `6a8972ed7b86446cda4f035cf30a61fd9ccdf6ab1a108d1c1478b9dce0d4eee4`.
- Protocol Architecture: `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.

ADR-022 checks current Mission existence/state. ADR-024 propagated its mode at
execution but explicitly left the Decision unbound to those terms. Changing a
Mission after deciding could therefore change what an earlier Decision executed.
All four initial DecisionMissionBindingTest regressions failed against that baseline.

## Decision

New decisions persist a complete snapshot of the currently modeled Mission fields:
ID, owner kind/ID, intent, ordered objectives, ordered participant IDs, mode and state.
The snapshot is captured from the persisted ACTIVE Mission inside the existing
decision transaction. It is stored with the Decision in the same SQLite INSERT.
`DecisionRepository.save(decision, missionSnapshot)` rejects a mismatched Mission ID
or inactive snapshot. `findMissionSnapshot(decisionId)` retrieves the historical terms.

Execution still requires the exact persisted Decision and an existing ACTIVE Mission.
It additionally requires equality between that Mission and the saved snapshot before
execution rights/consent evaluation and any Action/Event/journal/receipt writes.
An absent, stale, malformed or unsupported snapshot denies execution. Changed intent,
owner, objectives, participants or mode requires a fresh Decision through the ordinary
authority and rights checks. This boundary applies to ordinary and signed P05.

The snapshot is associated with the persisted Decision ID; caller-supplied Decision
objects cannot replace it. SQLite rejects updates to the snapshot or Mission reference,
including adding a snapshot to a previously unbound Decision. Duplicate Decision IDs
cannot overwrite the row. Repository access remains a trusted adapter boundary; this
does not authenticate callers or establish collective representation/participation.

The numeric `snapshotVersion = 1` identifies the serialization format. Mission has
no numeric revision, and this increment does not invent one. Equality describes
current modeled content, not a chronology of Mission changes: restoring exactly
the same ACTIVE terms makes the earlier snapshot match again. List order is retained.
Monotonic revisions, lifecycle/mode-change approval, participant admission and fields
not yet modeled in the MVP remain separate governance/model work.

## Action history and signed delivery

New ACTION_EXECUTED payloads include the full `missionSnapshot` object, produced
from the same matching Mission used for Action.mode. Ordinary history retains this
in its append-only local Event. SignedP05Runtime's existing canonical signature
binds all snapshot fields and the Decision reference without changing its envelope.
Existing peer context/origin/sharing policy still governs delivery; hosts must choose
appropriate sharing policies for the Mission terms included in new signed facts.

This is an execution fact, not a newly signed Decision/Mission producer or collective
approval proof. No causal parent Event is fabricated for a local Decision. Receiving
the fact does not materialize or execute remote Mission, Decision or Action entities.
P05 authority lineage, identity, affected-Person consent and rights checks remain.

Result reporting uses the already executed Action and its existing evidence checks,
not current Mission terms. Later Mission changes or legacy missing snapshots do not
rewrite executed history or invalidate otherwise permitted historical reporting.
Existing signed Action Events without `missionSnapshot` remain historical evidence
under their original contract; no snapshot is inferred or attached retroactively.

## Additive compatibility migration

Fresh Schema.sq defines nullable `decision.mission_snapshot_json`. SqlDelightStore
installs the immutable binding trigger on fresh and existing databases and adds the
missing column on older databases; duplicate-column handling is limited to that expected compatibility case.
Repeated opens are idempotent. All old Decision rows retain a null snapshot and their
existing fields; no current Mission is misrepresented as an earlier approved state.

Old decisions remain readable but cannot authorize a new execution. The operator
must create a new Decision under current authority/rights checks. Existing Actions,
Events, Results and signatures remain unchanged. The migration is exercised on a
pre-column SQLite schema with historical records and on repeated reopen.

The conditional OLD/NEW trigger uses the existing runtime compatibility pattern
also used for authority lineage: SQLDelight 2.0.2's schema parser does not resolve
those trigger references. Runtime tests verify the guard on both fresh and migrated stores.

## Evidence and limits

DecisionMissionBindingTest covers changed mode/owner/intent/objectives/participants,
ordinary/signed rejection after reopen, fresh-decision recovery, unbound legacy
records, signed snapshot integrity and historical Result continuity.
DecisionMissionPersistenceTest covers full-field round trips, immutable binding,
invalid snapshot IDs/states, migration without backfill, retry after failed INSERT,
rights ordering/denial and malformed or unsupported snapshots. MissionModeTest now
requires a fresh Decision after mode change and retains its rollback/retry coverage.
SignedActionTest and the HTTPS rehearsal exchange the new execution payload through
the existing recovery paths without remote business replay.

This closes content-snapshot binding. It does not implement numeric Mission versions,
Mission-change commands, execution idempotency, a general collective mandate protocol,
or complete authorization/consent rules for every modeled Mission participant.
