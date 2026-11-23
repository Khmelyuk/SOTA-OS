# Signed P05 Action and Result events

`api/agency/SignedP05Runtime` is an opt-in trusted-host composition implementing
`MissionActionProtocol`. It uses the existing P05 identity, persisted-decision,
authority-lineage, rights and affected-Person consent checks. A successful
`execute` creates a canonical Ed25519-signed `ACTION_EXECUTED` event for P09.

Construct it with the shared `SqlDelightStore`, `LocalSyncIdentity`, governance
context, clock, ID generator and an actor-bound `(SubjectRef) -> EventSigner`.
The host authenticates the invocation actor and protects private keys. Public
keys must already be provisioned through governed P09 operations; construction
neither enrolls actors nor creates keys. `runtime.sync` exposes that strict P09
composition for exchange and governed provisioning.

Each protocol call holds the store's local-access gate. P05's execution transaction
contains the Action, signed Event, sync journal entry and verified-key receipt.
Signing failure, missing/revoked/mismatched key, nonlocal actor or failed journal /
receipt insertion rolls back the action as well. The signer must match the current
SQLite actor key; historical approvals cannot authorize a new action producer.
A signed `recordResult` similarly commits Result, Event, journal and receipt together.
Other operations using this SQLite connection must use the same store gate.
Signer providers run inside the transaction and should use locally available keys.

The event retains P05 payload, authority/decision references, provenance and context.
Its initial legacy hash is replaced with the canonical P09 hash **before first
persistence**. Action records have no state assertion or causal parents: references to a
Decision or Authority are not invented references to journal events. Peer admission
still requires explicit actor, context, origin and sharing policy. Receiving this
fact does not execute an Action or create a remote Decision, Mission or Result.

This slice signs newly executed Action facts and newly recorded Results. Mission
formation and decisions remain local records without new signed event producers.
[Signed P07](p07-signed-memory.md) adds Experience and Knowledge producers.
`demo` remains the existing unsigned bootstrap path. Existing unsigned
events are never rewritten or skipped and can still block strict journal export.
There is no new CLI action command or general remote P05 command dispatcher.

`SignedActionTest` verifies canonical signing, strict exchange after lost reply and
SQLite reopen, replay without remote action execution, journal/receipt rollback,
key/local-actor rejection, persisted-decision protection, consent denial and revoked
authority. Existing P05 tests retain broader authority and rights coverage.

## Persisted Mission boundary — 2026-11-20

Both ordinary and signed P05 now require the referenced Mission to exist and be
ACTIVE when creating a Decision and again when executing it. A missing, suspended,
completed or otherwise inactive Mission cannot produce a new Action even while
its authority remains valid. The decision rights check receives the stored Mission
ID rather than relying on invocation context. Existing orphaned decisions remain
history and are denied at execution. Result reporting for an already executed
Action remains allowed after Mission closure, subject to its existing checks.
See [ADR-022](adr/ADR-022-active-mission-boundary.md) and MissionStateBoundaryTest.

## Mission/Action mode — 2026-11-21

New Actions copy the current persisted Mission mode at execution; their
ACTION_EXECUTED payload includes `mode` (`CREATION` or `PROTECTION`). Signed P09
integrity binds that value. Later Mission changes do not rewrite Action history.
Signed Result recording rejects a mode mismatch between the stored Action and
its execution Event when that field is present. Legacy signed Events without it
remain readable and usable, but provide no signed evidence of mode.
See [ADR-024](adr/ADR-024-mission-action-mode.md) and MissionModeTest. ADR-027 now
binds execution to the decided Mission terms; numerical revisions and policies
governing mode changes remain open.

## Decided Mission terms — 2026-11-23

Each new Decision stores the exact persisted ACTIVE Mission snapshot. Execution
requires all modeled Mission fields to match it, including owner, intent, objectives,
participants and mode. Changed terms require a fresh Decision under current authority
and rights checks. The snapshot and Decision share one INSERT; their binding cannot
be updated. Ordinary P05 uses the same boundary.

New ACTION_EXECUTED payloads include `missionSnapshot` with `snapshotVersion = 1`,
ID, ownerKind/ownerId, intent, objectives, participants, mode and state. The signature
binds these terms together with the existing execution references. This is not a
numeric Mission revision or a separate signed collective Decision. Existing peer
sharing policy governs the additional Mission terms in the signed execution fact.

An additive SQLite migration leaves old Decision snapshots null. They remain readable
but cannot authorize a new Action; create a new Decision. Existing Action/Result history
and old signatures remain usable under their previous contract without backfill.
See [ADR-027](adr/ADR-027-decision-mission-snapshot.md), DecisionMissionBindingTest
and DecisionMissionPersistenceTest. Restoring identical ACTIVE terms matches the old
snapshot again; a monotonic revision/lifecycle protocol remains separate work.

## Result producer — 2026-10-11

`recordResult` now requires the exact persisted Action in both signed and ordinary
P05 compositions. A caller cannot change its actor, authority, context or other
fields by supplying a copied object. The author must be the Action actor, and the
rights check uses the persisted Action context. This reports an existing execution;
it does not execute the Action again. Existing result authorization semantics remain:
there is no new requirement that execution authority still be active, and a host's
rights policy can deny reporting. Host authentication remains mandatory.

The signed composition additionally requires exactly one local `ACTION_EXECUTED`
journal record matching that Action, with canonical integrity and exact local
verification evidence. Its historical key may have rotated; a new Result must use
the actor's **current** provisioned key. Unknown, unsigned or remote-origin Action
history cannot be promoted to local signed Result history.

`RESULT_RECORDED` retains the Action actor, context, authority and decision references;
its timestamp is the Result timestamp, `resultRef` identifies the Result, and its
payload carries `actionId`, `outcome` and `description`. Both `parents` and
`provenance.sourceEventId` identify the signed Action event. The canonical signature
covers this link. It is a reported fact with no state assertion; receipt does not
create or execute remote Action/Result entities. Repeated calls create distinct
Results; this change does not introduce command idempotency or result replacement.

`SignedResultTest` checks signing and parent integrity, lost-reply recovery across
SQLite reopen, atomic rollback for Result/Event/journal/receipt failures, forged
Actions, wrong author/origin, rights denial, key rotation and unsigned-history denial.
The [HTTPS rehearsal](getting-started.md) now exchanges ten records: Action,
Result, Experience, Knowledge proposal and six exit stages. Ordinary unsigned `demo` behavior remains unchanged
apart from the documented source and Mission/Decision checks. The original Result
producer needed no schema migration; ADR-027 adds the Decision snapshot migration.
