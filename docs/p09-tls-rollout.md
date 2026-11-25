# P09 overlapping-trust TLS rollout rehearsal

Run from the repository with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./scripts/p09-tls-rollout.sh
```

The wrapper runs `:test:tlsRollout`. `TlsRolloutTest` executes the same assertions
in the ordinary test suite and GitHub CI. All material and databases are disposable
localhost fixtures; no deployed service or existing credentials are changed.

## Transition and acceptance checks

Each node has independent old and new certificates. The two generations use different
passwords. Public trust stores contain either the peer's old certificate, both old
and new certificates, or only the new certificate. Overlap stores are prepared under
each generation's password so a node's key store and outbound trust store use the
same password at every stage. Existing material is never rewritten in place.

| Stage | Server A | Server B | Outbound trust on both nodes | Records on each node |
|---|---|---|---|---|
| Baseline | Old | Old | Old only | 4 |
| Prepare overlap | Old | Old | Old and new | 8 |
| Switch A | New | Old | Old and new | 12 |
| Switch B | New | New | Old and new | 16 |
| Retire old trust | New | New | New only | 20 |

A fresh signed Core Loop adds four records at each stage. Real CLI `sync check` and
`sync compare` validate both nodes' staged configurations and correct password
sources before switching. These commands leave database bytes and configuration files
unchanged. The scenario then exchanges in both directions, recreating TLS contexts
and restarting real `sync serve` CLI listeners on their original ports.

Every stage must preserve the exact previous journal prefix, verify signatures from
the original authors, converge to identical journals, keep event IDs unique, and set
both peers' sent/received checkpoints to the record count. Repeated exchanges must
not duplicate records. Database handles close between accesses and before a separate
CLI process opens that node's database.

After retirement, four more records are queued. Live listeners using old, still-valid
certificates are started separately on each peer's original port. New-only client
trust must reject both with an SSL handshake failure, using otherwise valid bearer
credentials. Both journals and checkpoints must remain unchanged. The new bundles
are then restored; wrong bearer tokens still receive HTTP 401, and valid exchanges
converge to **24 identical signed records and `(24, 24)` checkpoints on both nodes**.

## Evidence and limits

The standalone task prints its private artifact directory and writes `report.txt`
only after every assertion passes. It retains synthetic databases, phase configuration
files, preflight logs and listener logs. Temporary keys, trust stores and password/token
files are removed on normal completion or ordinary exceptions; successful removal
is checked before writing the report. Abrupt process termination can prevent cleanup.
The test-suite wrapper removes its entire directory. Fixture material is not reusable
deployment material; in-memory signing keys are not persisted.

The receiving side uses real CLI listeners; the sending side uses production P09
runtimes and HTTPS transport in the fixture host. This does not operate two continuous
`sync run` services or promise uninterrupted availability during restart. The scenario
uses explicitly trusted self-signed leaf certificates; it does not prove a particular
CA chain, revocation service or public deployment. It adds no issuer selection,
automatic certificate issuance, trust-distribution service or production rollout policy.

See [material rotation](p09-material-rotation.md), [rollback](p09-tls-rollback.md) and
[ADR-029](adr/ADR-029-p09-tls-bundle-comparison.md). The next independent engineering
track is release-migration acceptance: supported source versions, an explicit upgrade
matrix, preservation requirements and failure/retry criteria.
