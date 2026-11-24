# ADR-028 — Local P09 server certificate expiry diagnostics

Date: 2026-11-24
Status: Accepted

## Context

P09 already validates explicit PKCS12 material and supports staged TLS replacement
by restart. `sync check --config PATH` rejects expired server leaves, but previously
provided no inventory or early indication that otherwise usable material needs renewal.
Operators need that information before stopping a working node.

## Decision

Extend the existing local preflight without adding a scheduler, issuer, network
probe or configuration mutation. For every private-key entry in the server keystore,
print a deterministic alias-sorted `P09 TLS certificate ` line followed by JSON:
alias, SHA-256 of the DER leaf certificate, notBefore/notAfter, observedAt,
secondsRemaining and state. Trusted-certificate-only entries are excluded from this
server inventory. JSON escaping prevents control characters in aliases from becoming
terminal line breaks or escape sequences. No key bytes, password or bearer token
are part of the diagnostic record.

The diagnostics and strict server validation use the same loaded keystore and
millisecond-resolution observation. The usual server startup still uses the current
clock and explicit trust; its validation is not relaxed. The validity endpoints are
inclusive, following [JDK 21 X509Certificate.checkValidity](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/cert/X509Certificate.html#checkValidity(java.util.Date)).

States are:

- NOT_YET_VALID before notBefore; strict preflight fails.
- EXPIRED after notAfter; strict preflight fails.
- RENEW_SOON while valid with at most 30 days remaining; preflight may succeed.
- VALID while valid with more than 30 days remaining.

Thirty days is an advisory project default, not an issuer requirement or a change to
certificate trust. Remaining seconds are the floor of the signed duration; an
expired certificate can have a negative value. The validity decision uses exact
instants at the stated millisecond precision, not rounded seconds.

Diagnostics are printed before date validation so an invalid certificate's cause is
visible. They can precede a later failure, including invalid client trust or unusable
private-key material. Only the existing final local-success summary means all local
preflight steps completed. A renewal hint appears only after those steps succeed.

## Boundaries

The check does not open SQLite, bind the listener, contact peers, issue certificates,
rotate keys/tokens or change trust. It reports every configured key-entry leaf;
it does not identify which alias a future handshake will select. It does not verify
hostname acceptance, remote trust, certificate-chain/anchor lifetime, revocation or
peer provisioning. A successful snapshot cannot guarantee that subsequently replaced
files or a later handshake remain valid.

This implements operational diagnostics under ADR-015/017 and the existing material
rotation runbook. It adds no collective mandate or automatic renewal policy.

## Evidence

Six `P09CertificatePreflightTest` cases cover inventory/fingerprints, inclusive
validity and the 30-day threshold, millisecond normalization, missing key/wrong
password, read-only occupied-port checks, escaped aliases, secret exclusion, invalid
date failures and failure after leaf diagnostics when client trust is empty.

The CLI smoke uses real PKCS12 files and checks fingerprint matching, the renewal
hint, expired/future rejection, occupied-port operation and unchanged SQLite bytes.
Existing strict HTTPS trust/hostname and rotation tests remain in the full suite.

Next: compare current and staged TLS bundles before replacement, with explicit
operator recovery checks. Automatic issuance and issuer selection remain separate.
