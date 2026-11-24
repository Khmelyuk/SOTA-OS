# ADR-029 — Read-only comparison of staged P09 TLS bundles

Date: 2026-11-24
Status: Accepted

## Context

ADR-028 gives operators local certificate expiry diagnostics. TLS replacement still
uses a deliberate restart with versioned files. Before that restart, operators need
to see which server certificates and client trust certificates change, and whether
the previous bundle remains locally usable for recovery.

## Decision

Add `sync compare --config CURRENT --candidate STAGED`. Both inputs are full version-1
`sync run` configurations. No other flags or database override are accepted. Existing
configuration validation and relative-path resolution apply independently to each
file. All resolved settings except `keystore` and `truststore` must match exactly,
including database path, node/actor, peer/endpoint, governance context and scheduling.
An omitted default and an explicitly specified default are deliberately distinct;
comparison is not a general configuration migration tool or filesystem identity test.

Both bundles use the existing single `SOTA_P09_TLS_PASSWORD` or
`SOTA_P09_TLS_PASSWORD_FILE` source. The existing outbound token source is validated
once for format. Independent candidate passwords and credential rotation are outside
this increment. No password or token is printed or added to configuration files.

Load each server store once, check every key-entry leaf at one shared millisecond
observation, and initialize its key manager. Load each client store once and initialize
its default JDK trust manager. Fingerprint the X.509 certificates returned by
[X509TrustManager.getAcceptedIssuers](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/X509TrustManager.html#getAcceptedIssuers()).
Require a nonempty X.509 trust inventory for comparison. Normal server/client startup
retains its existing validation behavior.

Only after both bundles pass, emit `P09 TLS comparison ` followed by one JSON object:

- `observedAt`: shared UTC observation time.
- `current` and `candidate`: alias-sorted `serverLeaves` with ADR-028 metadata,
  plus sorted, deduplicated `clientTrust` SHA-256 DER fingerprints.
- `serverLeaves` and `clientTrust`: sorted `added`, `removed` and `retained`
  fingerprint sets, relative to the current bundle.
- `currentRecovery: LOCAL_MATERIAL_VALID`: previous material passed local checks at
  this observation, not a promise of future rollback or remote acceptance.

Changing aliases or PKCS12 serialization does not itself change fingerprint sets.
Server aliases remain visible in the inventory; comparison does not predict which
alias a handshake selects. All alias text uses JSON escaping. Changes to trust are
reported for operator review, not automatically accepted or rejected as a rollout policy.
Invalid current or candidate material fails with no comparison/success record. Use
`sync check` on the individual bundle for detailed certificate diagnostics.

## Recovery and boundaries

This command does not open SQLite, bind a port, contact peers, replace files, issue
certificates, rotate credentials or modify trust. It observes files supplied by the
operator; it does not attest what a running process has loaded. The two stores and
two configurations are sequential snapshots, not an atomic filesystem snapshot.
Keep versioned paths stable through preflight and restart.

A matching trust fingerprint inventory does not prove chain, hostname, revocation,
remote authorization or connectivity acceptance. Trust-anchor expiry and full chain
validation are not added. Near-expiry current material may pass; recovery may cease
to be possible later. Before recovery, stop the candidate, rerun `sync check` with
the previous environment/configuration, then restart only that host. Verify an actual
authenticated exchange and checkpoints on both peers. Never run two hosts over the
same database or bypass TLS validation to recover. See the [rotation runbook](../p09-material-rotation.md).

## Evidence and next step

Six `P09MaterialComparisonTest` cases exercise unchanged/replaced server leaves,
overlapping and removed trust, duplicate anchors, escaped aliases, configuration drift,
invalid CLI shape, wrong password, missing private key, empty/non-X.509 trust,
expired candidate/recovery material and immutable files with an occupied port.
The real CLI smoke verifies unchanged/replaced fingerprints, file-backed secrets,
configuration drift, overrides and invalid dates in either bundle without database writes.
Existing HTTPS material-rotation and two-node recovery scenarios remain regression checks.

Next bounded increment: independently supplied current/candidate TLS passwords for
staged password changes, preserving explicit source selection and failure behavior.
Automatic issuance, issuer choice and broader configuration rollout remain separate.
