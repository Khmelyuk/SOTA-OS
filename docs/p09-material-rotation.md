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

## TLS replacement sequence

1. Obtain replacement TLS material through your existing trusted issuer/operator.
   For a CA change, distribute a client trust bundle covering the transition before
   switching servers; for the same trusted CA, a client trust change may be unnecessary.
2. Stage the server key/certificate, truststore, password and configuration together
   under versioned paths. Keep the previous valid bundle available for rollback.
3. Run `sync check` with the candidate environment/configuration. Resolve validation
   errors before stopping the current node. The running process keeps its loaded TLS
   context while files are staged.
4. Stop the old host cleanly. Start `sync run --config ...` with the candidate bundle,
   the same database and node/actor IDs. Do not run two hosts over the same SQLite file.
5. Verify a successful exchange in status/metrics on each side. On failure, stop the
   candidate and restart with the previous still-valid bundle; never disable trust or
   hostname verification. Journals/checkpoints survive either restart.

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

Verification — 2026-10-07: full JDK 21 build, Detekt, migrations and 206 tests
passed with no failures/errors/skips. Extended CLI smoke passed.
