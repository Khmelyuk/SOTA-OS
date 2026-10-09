# Signed P05 action events

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
Other operations using this SQLite connection must use the same store gate.
Signer providers run inside the transaction and should use locally available keys.

The event retains P05 payload, authority/decision references, provenance and context.
Its initial legacy hash is replaced with the canonical P09 hash **before first
persistence**. Records have no state assertion or causal parents: references to a
Decision or Authority are not invented references to journal events. Peer admission
still requires explicit actor, context, origin and sharing policy. Receiving this
fact does not execute an Action or create a remote Decision, Mission or Result.

This slice signs only newly executed action facts. Mission formation, decisions,
results, Experience and Knowledge are still local records without new signed event
producers. `demo` remains the existing unsigned bootstrap path. Existing unsigned
events are never rewritten or skipped and can still block strict journal export.
There is no new CLI action command or general remote P05 command dispatcher.

`SignedActionTest` verifies canonical signing, strict exchange after lost reply and
SQLite reopen, replay without remote action execution, journal/receipt rollback,
key/local-actor rejection, persisted-decision protection, consent denial and revoked
authority. Existing P05 tests retain broader authority and rights coverage.
