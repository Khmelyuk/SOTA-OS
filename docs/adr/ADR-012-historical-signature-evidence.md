# ADR-012 — Locally verified history after actor-key retirement

Status: IMPLEMENTED for exact locally verified records
Date: 2026-09-29

## Problem

Strict P09 previously verified every replay/export with the current actor key.
Rotation or revocation therefore blocked already accepted history, including P10
stage records. Trying all retired keys based on the event timestamp is unsafe:
a holder of a retired key can sign an unknown record with an earlier timestamp.
Neither the signature nor that sender-chosen timestamp proves when it was created.

## Decision

Persist an append-only local verification receipt with every newly appended
production sync record. The receipt contains the event ID, complete canonical
SyncRecord (including signature, actor, origin, parents and assertion), verified
public key and key ID, and the local verification time. No private key is stored.
Verification time is audit context, not a rule for accepting backdated records.

`VerifiedRecordSignatures.remember` verifies strict record integrity and the
current actor signature inside the event/journal transaction. Failure rolls back
the event, journal, receipt and surrounding P10 stage or inbound P09 checkpoint.
Admission checks alone never create receipts. Inbound failure at a later export
policy check rolls the receipt back as well. Key lookup is repeated at append,
so a newly retired key cannot pass by racing a previous admission snapshot.

For later checks, a receipt supplies the historical verifier only when the entire
canonical record is byte-identical. `EventSignatureAdmission` still verifies the
signature, and ProductionPeerAdmission still applies current peer credentials,
direction, context, actor-origin bindings, origin trust and assertion allowlists.
A receipt is evidence of prior local verification, not an authorization override.

| Input | Result |
|---|---|
| Exact locally verified record, key rotated or revoked | Historical signature can be verified and record replayed/exported, subject to current sharing policy |
| Unknown record signed with a retired key | Denied, regardless of claimed event time |
| Same event ID with changed payload, signature, parents, origin or assertion | Denied; existing history cannot be substituted |
| Valid current-key record with no receipt | Normal admission; receipt commits only with the newly appended journal record |
| Valid historical receipt but peer/origin revoked or sharing scope denied | Denied |

Local production producers are checked against LocalSyncIdentity actor bindings
and provenance. Their event, journal and receipt commit together before any
network exchange. The signed P10 producer uses this path without rewriting events.

## Migration and limits

The SQLite migration is additive and idempotent. It creates an empty receipt table;
it does not infer past verification from old event timestamps or key audit rows.
Previously stored records remain readable. Old journals without receipts do not
automatically gain retirement-proof verification; after key retirement they fail
closed. A separate reviewed migration policy would be required to change that.

Receipts are local and never accepted from a sender. A new/offline receiving node
that has never verified an old-key record cannot import it after retiring that key.
Recovery across nodes with different key histories, transferable trusted timestamp
proofs and retrospective compromise policy remain separate work. Revocation stops
new old-key records; it does not erase accepted history or assert that all past
statements made by a compromised key were true. Peer revocation still stops sharing.

The host protects SQLite and provisioning credentials. Repository ports and receipt
writes are trusted infrastructure, not externally exposed APIs. Existing strict
requirements for legacy unsigned producers remain unchanged.

## Evidence

HistoricalSignatureTest exercises retirement plus restart/replay/export, backdating,
full-record substitution, current peer trust, receipt and inbound rollback,
append-only enforcement, additive migration without invented evidence, and local
producer failure after retirement. Existing signed P10 and production peer tests
continue to verify origin policies, atomic stages and synchronization recovery.

Full JDK 21 build on 2026-09-29 passed: 123 tests, zero failures/errors/skips,
Detekt and migration verification included.
