# ADR-016: Local governance approval for one historical signed record

Status: Accepted — 2026-09-30

## Problem

ADR-012 preserves exact records already verified locally. A newly provisioned node
may know only an author's current key and cannot verify previously unseen records
from an older key. Accepting any old-key signature with an earlier event timestamp
would also admit newly forged, backdated records. TLS and another node's local
receipt do not establish historical trust on the receiving node.

## Decision and trust model

Introduce an explicit local administrative decision about **one exact record and
one public key**. An authenticated, independently delegated operator must review
both the record and the claimed historical actor/key binding using evidence trusted
outside the sync request. The application requires an evidence reference and logs
it; it does not itself fetch or establish the truth of that evidence.

`P09Runtime.historicalApprovals.approve` requires `history.approve` and the exact
resource returned by `historicalRecordTarget(record)`: `history:CONTENT_HASH`.
The hash is the integrity-checked, domain-separated P09 digest over the event and
its origin, parents and assertion. The approval stores the complete canonical
record including signature, actor, old key ID/public key, operator, delegated
authority, purpose, evidence reference and local approval time. Actor and origin
cannot approve their own history. Agent approval is prohibited. Current authority
lineage, governance context and RightsConstraint are checked for every operation.

This is a local acceptance decision, not a trusted timestamp or proof that a
statement is true, that a signature predates retirement, or that a compromised key
was uncompromised when it signed. There is no automatic approval from peer claims,
key audit rows, event times, HTTPS, or another node's verification receipts. A host
must display the full record, key fingerprint, digest and evidence to its operator
before invoking the service. No network or CLI approval route is introduced.

Approval does not replace the actor's current key, write an Event, advance a
checkpoint or fabricate past verification. Approval of a local-origin record is
rejected, so it cannot revive retired keys for local producers. The exception is
not a wildcard over the key, actor, origin or time interval. Altered record content,
metadata, signature or a different event ID cannot use the approval.

## Import and receipt semantics

Current peer credentials, direction, sharing context, allowed origins, actor-origin
bindings, assertion scope, integrity, signature and causal validation still apply.
The approval only supplies an otherwise unavailable verifier for the exact record.
The append hook re-reads the approval and revocation state before verifying and
committing. An earlier admission lookup does not reserve permission.

On successful first import, Event, journal, checkpoint and verification receipt
commit together. The receipt has the actual local verification time and a
`historicalApprovalTarget` link to the immutable decision. A failure, including
later export-policy denial, rolls back imported data and receipt; a previously
committed governance approval remains available for retry. The receipt is local
and is not included in the P09 wire format.

## Revocation

`history.revoke` with the same exact resource and current independent authority
appends a revocation with actor, authority, purpose and time. Approval/revocation
and the common provisioning audit share the same SQLite transaction. Neither
record can be rewritten or deleted, and a revoked approval cannot be replaced.
Administrative decisions remain durable until explicitly revoked; later retirement
of the operator's authority does not silently rewrite those decisions.

Before an import commits, revocation removes the historical exception. It is not
a blocklist for a signature independently valid under the current key. After an
import commits, ADR-012's exact local receipt remains valid evidence of acceptance;
revoking the approval does not erase history. Current peer revocation or sharing
policy can still prohibit replay/export. Retroactive content invalidation after a
compromise is a separate workflow, not implemented by deleting receipts.

## Migration and limits

Additive startup migration creates empty approval/revocation tables and adds a
nullable source link to existing verification receipts. Existing receipts retain
their data with a null link; no approval or past verification is inferred. A signed
legacy journal without a receipt may use an explicit matching active approval for
verification, but admission does not manufacture a receipt for an already stored
row. It continues to depend on that approval until normal local evidence exists.

Automatic cross-node attestations, trusted timestamp infrastructure, bulk historical
manifests, operator review UI and compromise adjudication remain future work. Hosts
protect SQLite and authenticate local operators as for peer/key provisioning.

## Evidence

HistoricalApprovalTest: a new node with only a current key, restart/import/replay,
exact-record binding, unknown backdated records, current policy, scoped independent
authority, RightsConstraint, missing evidence, invalid signatures and local-origin
denial. HistoricalApprovalDurabilityTest: audit/receipt/export rollback, append-time
recheck, append-only audit and additive migration preserving existing receipts.
HistoricalApprovalHttpsTest: an old-key source record is rejected over real HTTPS,
then imported after local approval and receiving-node restart, with safe replay.
Existing historical-signature and signed P10 tests continue to pass unchanged.
