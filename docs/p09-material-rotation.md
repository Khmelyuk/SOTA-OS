# Updating P09 TLS and peer credentials

P09 supports controlled TLS replacement by restart and file-backed outbound bearer
credential updates between attempts. Peer enrollment, rotation and revocation remain
independently authorized operations with revision checks and audit. Loading a file
never enrolls a peer, changes a policy or grants authority.

## Secret delivery

For each secret choose exactly one source:

| Direct environment value | Environment variable containing a file path |
|---|---|
| `SOTA_P09_TLS_PASSWORD` | `SOTA_P09_TLS_PASSWORD_FILE` |
| `SOTA_P09_PEER_TOKEN` | `SOTA_P09_PEER_TOKEN_FILE` |

Both sources set, neither set, empty content or malformed content fail closed.
There is no fallback to a previously valid token. Files must be regular, UTF-8 and
at most 4096 bytes. One terminal LF or CRLF is removed; other line breaks and NUL
are rejected. Password spaces are preserved. Bearer tokens cannot contain whitespace.
The file path is trusted local input; use absolute paths in a protected local
folder and permissions such as 0600. Symlink-based secret mounts are allowed;
this is not a defense against a malicious user who controls the parent directory.

Example environment (paths only, no secret values):

```bash
export SOTA_P09_TLS_PASSWORD_FILE=/private/sota/tls.password
export SOTA_P09_PEER_TOKEN_FILE=/private/sota/peer.token
```

Unset the corresponding direct environment variables when using files. Passwords
for the server PKCS12 and client truststore currently must match. TLS material is
loaded only at startup; bearer files are validated at startup and reread before
each outbound exchange. A missing/invalid token file causes retry failure before
an HTTP request is sent. Environment-backed tokens stay fixed for that process.
File byte buffers and TLS password character buffers are cleared after use; this
does not promise erasure of all JVM Strings or key objects.

## Preflight a candidate bundle

Prepare new keystore, truststore and configuration under versioned paths. With the
candidate's secret environment, run the assembled CLI:

```text
api sync check --config /private/sota/releases/new/node.conf
```

`check` requires a full `sync run` configuration. It validates schema/options,
nonempty database-file presence, secret source format, PKCS12 passwords/private key
availability and server X.509 leaf validity for every key entry. Client trust must
be explicit and nonempty. It does not open SQLite, bind the port, contact a peer,
inspect peer registry/key authority or prove hostname/connectivity/remote acceptance.
A successful check is not a promise that files changed afterward remain valid.

The check now prints one `P09 TLS certificate ` line followed by JSON for each
server private-key entry, sorted by alias. Fields include the alias, SHA-256 leaf
fingerprint, UTC `notBefore`, `notAfter`, `observedAt`, signed `secondsRemaining`
and `state`. Alias text is JSON-escaped. Only public certificate metadata is printed.

| State | Meaning | Local check |
|---|---|---|
| VALID | Valid with more than 30 days remaining | May succeed |
| RENEW_SOON | Valid with 30 days or less remaining | May succeed; stage a replacement |
| NOT_YET_VALID | Before the certificate's start time | Fails |
| EXPIRED | After the certificate's end time | Fails |

The 30-day window is advisory. Validity endpoints are inclusive; remaining seconds
are rounded down, while validity checks use millisecond precision. Diagnostics and
validation use the same loaded keystore. Rows may be printed before a later failure;
the final local-success summary appears only after all material checks complete.
A renewal hint is printed after a successful check when at least one leaf is near expiry.

This inventory covers configured server leaves, not chain/anchor validity or the
alias a future handshake will select. It does not prove remote trust, hostname
acceptance, revocation status or peer authorization. See
[ADR-028](adr/ADR-028-p09-certificate-diagnostics.md) for the contract and evidence.

## Compare current and staged bundles

For a TLS-only replacement, run:

```text
api sync compare --config /private/sota/releases/current/node.conf --candidate /private/sota/releases/new/node.conf
```

Both files must be full `sync run` configurations with identical resolved settings
except `keystore` and `truststore`. Relative paths resolve against each configuration's
directory, so both database paths must resolve to the same path. Explicit defaults
must match too. Other flags and `--db` overrides are rejected.

The current bundle uses the existing TLS password source. For a different candidate
password, select exactly one of `SOTA_P09_CANDIDATE_TLS_PASSWORD` and
`SOTA_P09_CANDIDATE_TLS_PASSWORD_FILE`. For example, retain the current password file
and select a separate, protected candidate file before running comparison:

```bash
export SOTA_P09_TLS_PASSWORD_FILE=/private/sota/releases/current/tls.password
export SOTA_P09_CANDIDATE_TLS_PASSWORD_FILE=/private/sota/releases/new/tls.password
```

Unset the corresponding direct-value variables when using files. Candidate files
follow the same UTF-8, size and single-line rules as other secrets. If neither
candidate variable is set, comparison uses the current password for both bundles.
An explicitly configured candidate source never falls back: ambiguity, invalid
content, a missing file or a wrong password fails comparison. Keystore and truststore
within each bundle still need the same password. Comparison does not re-encrypt files.

Candidate variables apply only to `sync compare`. Before checking or starting the
candidate on its own, point `SOTA_P09_TLS_PASSWORD_FILE` at its password file and
unset the candidate variable to avoid stale comparison settings. For recovery, select
the previous runtime password source explicitly; keep both versioned bundles and
their protected password files available until the rollout has been verified.

After both bundles pass local validation, one `P09 TLS comparison ` JSON record
reports both server-leaf inventories and client-trust fingerprints, plus sorted
`added`, `removed` and `retained` fingerprint sets. Trust additions and removals
need operator review on both peers; the command does not approve a trust policy.
Duplicate certificate fingerprints are collapsed in sets; aliases remain visible
in the leaf inventories. The record contains public metadata only.

`currentRecovery=LOCAL_MATERIAL_VALID` means the previous bundle passed local
checks at the reported time. It does not guarantee later recovery, remote trust,
hostname acceptance or an authenticated exchange. If either bundle is invalid,
comparison fails without a success record; run `sync check` separately to diagnose it.
Keep versioned files stable: comparison observes supplied files, not the running
process's loaded context. Details: [ADR-029](adr/ADR-029-p09-tls-bundle-comparison.md).

## TLS replacement sequence

1. Obtain replacement TLS material through your existing trusted issuer/operator.
   For a CA change, distribute a client trust bundle covering the transition before
   switching servers; for the same trusted CA, a client trust change may be unnecessary.
2. Stage the server key/certificate, truststore, password and configuration together
   under versioned paths. Keep the previous valid bundle available for rollback.
3. Run `sync check` with the candidate environment/configuration, then `sync compare`
   with the current password source and optional independent candidate source. Review the leaf and
   client-trust changes on both peers. Resolve validation
   errors before stopping the current node. The running process keeps its loaded TLS
   context while files are staged.
4. Stop the old host cleanly. Start `sync run --config ...` with the candidate bundle,
   the same database and node/actor IDs. Do not run two hosts over the same SQLite file.
5. Verify a successful exchange and journal checkpoints in status/metrics on each side.
   On failure, stop the candidate, rerun `sync check` with the previous environment
   and configuration, and restart with that still-valid bundle. If the previous material
   has expired or no longer works with peer trust, obtain valid material and coordinate
   trust recovery; never disable trust or hostname verification. Confirm an authenticated
   exchange after recovery. Journals/checkpoints survive either restart.

There is no live SSLContext swap, certificate issuance, ACME client or expiry daemon.
Rejected TLS connections do not advance the local sync checkpoint. Local preflight
checks current leaf validity, not the complete remote trust path or revocation status.

## Peer token rotation sequence

1. On the receiving node, invoke the existing governed `peerProvisioning.rotate`
   operation with an independently authenticated operator, active scoped authority
   and expected revision. It commits the verifier and audit and returns the new token.
   The previous token immediately loses acceptance; there is no overlap period.
2. Deliver that token to the sending node through your authorized secret-distribution
   channel. This implementation does not transmit the token for you.
3. Write a complete replacement file in the same protected directory, set its private
   permissions, then atomically rename it over the configured token path. Do not edit
   the live file in place. Follow your filesystem's durability procedure if crash-safe
   delivery is required.
4. The next attempt reads the new file. An in-flight request may still carry the old
   token and fail; capped retries resume without skipping journal records or relaxing
   admission. A revoked peer stays rejected even if its token file is readable.

Use the host API for governed provisioning inside a running process. Separate CLI
processes do not share its SQLite gate. Do not substitute direct SQL edits for
provisioning. There is no automatic rollback to an old credential after rotation.

## Verification

`P09SecretsTest` verifies explicit source selection, bounded UTF-8 files, malformed
and missing material, rereading and password whitespace. `P09MaterialRotationTest`
uses real HTTPS to verify old-token rejection, atomic replacement with the same
client, revocation, new TLS certificates, trust rejection and recovery after both
stores reopen. `P09TlsTest` rejects an expired replacement certificate before listening.
The CLI smoke checks file sources and preflight while the candidate port is occupied,
asserting that the SQLite file remains byte-for-byte unchanged.

`P09CertificatePreflightTest` adds six cases for fingerprint inventory, validity and
renewal boundaries, invalid material, escaped aliases and read-only/secret-safe output.
The CLI smoke also checks the real certificate fingerprint and renewal hint, and
requires nonzero exit for expired and not-yet-valid replacement certificates.

`P09MaterialComparisonTest` adds six cases for fingerprint deltas, overlapping/removed
trust, stable node settings, invalid material and read-only comparison. The CLI smoke
also compares real unchanged/replaced bundles, rejects configuration drift/overrides
and invalid dates in either bundle, and verifies unchanged files with an occupied port.

`P09ComparisonSecretsTest` and `P09ComparisonPasswordTest` add seven tests for independent
password sources, strict selection, malformed input, buffer cleanup, re-encrypted
stores with unchanged certificate fingerprints and failure without writes or fallback.
CLI smoke verifies both source forms, rejection of wrong/ambiguous/missing sources,
and explicit password selection for candidate activation and previous-bundle recovery.

Verification — 2026-10-07: full JDK 21 build, Detekt, migrations and 206 tests
passed with no failures/errors/skips. Extended CLI smoke passed.
