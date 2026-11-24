# P09 failed TLS replacement and rollback rehearsal

Run from the repository with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./scripts/p09-tls-rollback.sh
```

The wrapper runs `:test:tlsRollback`. The same scenario runs as `TlsRollbackTest`
in the ordinary test suite and GitHub CI. It uses disposable localhost fixtures,
production P09 runtimes and real CLI `sync check`, `sync compare` and `sync serve`
processes. No production database, deployed service or existing TLS material is used.

## What the scenario verifies

1. Bootstrap two independent SQLite nodes with governed peer/key admission and
   ephemeral Ed25519 signers. Execute one signed Core Loop on node A and exchange
   its four records over the previous TLS material.
2. Close the listener and execute a second signed Core Loop locally on B, leaving
   four pending records. Stage different server certificates, client trust and an
   independent TLS password. The candidate passes real CLI preflight and comparison;
   those read-only commands leave both database files byte-for-byte unchanged.
3. Start B's candidate CLI listener on the original port. A retains its previous
   trust and rejects the candidate certificate with an SSL handshake failure. Then
   B's candidate outbound trust rejects A's unchanged certificate. Both attempts use
   the appropriate existing bearer credential; TLS fails before authenticated exchange.
4. Stop each listener before inspecting its database. Both journals and both
   checkpoints must exactly match their snapshots before the failed exchanges.
5. Reject preflight of the previous bundle using the candidate password, then pass
   preflight with the previous password. Restart B on the original port with the
   previous certificate and file-backed password. Recreate clients with previous
   trust/password sources. A deliberately wrong bearer still receives HTTP 401.
6. Exchange in both directions using the original valid credentials. Repeat exchanges
   and reopen SQLite between accesses. Require eight identical signed records, unique
   event IDs and `(sent=8, received=8)` on both nodes. Verify every signature against
   its original node signer. Configuration, certificate/trust stores and password
   files must retain their original hashes.

This checks recovery after both inbound and outbound TLS incompatibility; it does
not advance checkpoints on a failed exchange, invent records or relax authentication.
The counterpart listener runs in a separate JVM; the parent closes its SQLite handle
before that listener starts. Two processes never open the same node database at once.

## Evidence and artifacts

The standalone task prints the artifact directory and writes `report.txt` after all
assertions pass. Expected final evidence includes:

```text
PASS P09 TLS replacement failure and rollback
Both journals: 8 identical signed records; both checkpoints: sent=8 received=8; no duplicates.
```

The private temporary directory retains synthetic databases, configuration paths,
preflight logs and listener logs for inspection. PKCS12 material, plaintext password
files and the token file are removed on normal completion or an ordinary exception;
the test also verifies removal before writing the success report. In-memory fixture
signers are not persisted. Logs are checked for plaintext fixture secrets during
preflight. An abrupt process/host termination can prevent cleanup; do not reuse these
fixtures as deployment credentials. The test-suite wrapper removes its entire directory.

## Scope and next step

The outbound side uses the real P09 runtime and HTTPS transport in the fixture host;
the receiving side uses the real `sync serve` CLI. This is not a simultaneous pair of
background `sync run` services or an automated deployment/rollback mechanism.
The fixture certificate issuers, root provisioning and recovery choice are explicit
test inputs, not a new governance policy. Local preflight success still does not prove
peer acceptance, as the rejected candidate demonstrates.

For operator steps, see [TLS material rotation](p09-material-rotation.md) and
[ADR-029](adr/ADR-029-p09-tls-bundle-comparison.md). The next operational rehearsal is
a successful transition with overlapping trust, followed by removal of retired trust
after both peers have switched. Issuer selection and automated issuance remain separate.
