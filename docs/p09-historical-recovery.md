# Recovering one historical P09 record on a new node

Use this when a node has never verified a signed record and the author's key has
already changed. Normal P09 still rejects that record by default. ADR-016 adds a
local governed exception for an exact record; it does not reactivate an old key.

## Review and authorize

1. Obtain the complete canonical SyncRecord and historical Ed25519 public key.
   Independently review the record and actor/key binding using your trusted archive
   or governance process. Neither the event's timestamp nor a peer-supplied receipt
   alone establishes when the signature was created.
2. Present the full content, origin, parents, assertion, signature, key fingerprint
   and integrity digest to the authenticated local operator. Record an evidence
   reference for that review; the service stores the reference without verifying
   an external document automatically.
3. Give that operator independent, currently valid authority in the configured
   governance context for `history.approve` and the exact resource
   `historicalRecordTarget(record)`. The author and origin cannot self-approve.
4. Call the local composition root from the trusted adapter:

```kotlin
val approval = runtime.historicalApprovals.approve(
    authenticatedInvocation,
    delegatedAuthorityId,
    reviewedRecord,
    ActorSigningKeyRecord(reviewedRecord.event.actor, historicalKeyId, publicKeyBase64),
    evidenceReference
)
```

The key is an Ed25519 X.509 public key encoded as Base64, as in normal actor-key
provisioning. The approval persists immediately, with audit, but imports no event.
The current actor key stays unchanged. The host must not expose this invocation
as an unauthenticated API or interpret an incoming Event as an approval command.

## Resume and inspect

Resume ordinary `sync once` / `P09Runtime.synchronize`, or start `sync serve` and
let the source retry. Peer credentials and current sharing policy must permit the
record. Missing causal parents must be resolved through the normal admission path;
an approval does not waive causal checks. After success, inspect the local
VerifiedRecord's `historicalApprovalTarget` to trace the verification to its approval.
A retry or restart preserves the decision and does not duplicate the event.

To withdraw an unused exception, an independently authorized operator needs
`history.revoke` for the same exact resource:

```kotlin
runtime.historicalApprovals.revoke(authenticatedInvocation, delegatedAuthorityId, eventId)
```

Revocation is append-only and stops first admission via that approval. It does not
erase an already committed local verification receipt, authorize replacement content
under the same event ID, or invalidate a signature accepted independently under a
current key. Use current peer/sharing policy to stop further exchange; retrospective
compromise/content adjudication remains separate.

## Verification

```bash
./gradlew :test:test --tests 'sotaos.test.p09.HistoricalApprovalTest' --tests 'sotaos.test.p09.HistoricalApprovalDurabilityTest' --tests 'sotaos.test.p09.HistoricalApprovalHttpsTest' --console=plain
```

The HTTPS test creates two temporary SQLite nodes, rotates the source actor key,
verifies initial rejection, approves one record on the receiver, reopens its store
and completes HTTPS synchronization. The approval is local; it is not transferred
from the source. See [ADR-016](adr/ADR-016-historical-record-approval.md) for the trust
assumption and [the HTTPS runbook](p09-https-pilot.md) for host lifecycle constraints.

Full JDK 21 build, Detekt and migration verification passed on 2026-09-30:
170 tests, zero failures/errors/skips, including 16 historical-approval tests.
