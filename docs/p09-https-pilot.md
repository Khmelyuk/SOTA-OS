# P09 two-node HTTPS pilot

This pilot connects two independent SQLite nodes through `P09Runtime`, with real
Ed25519 records, governed peer/key enrollment, TLS certificate verification and
bearer credentials. It exercises transport and recovery; received facts do not
execute remote membership, authority or contract commands.

## Reproducible local verification

With JDK 21 selected:

```bash
./gradlew :test:test --tests 'sotaos.test.p09.HttpsProductionPilotTest' --tests 'sotaos.test.p09.P09HttpsBoundaryTest' --tests 'sotaos.test.p09.P09TlsTest' --console=plain
```

The fixture creates two temporary SQLite files. Independent operator authority
allows each runtime to enroll the other node and both author public keys. It then:

1. Records signed facts independently while disconnected.
2. Exchanges over localhost HTTPS and discards the successful response to model
   an acknowledgement lost after the receiving transaction committed.
3. Closes the listener and both stores, reopens SQLite, and reconstructs runtimes.
4. Retries with the saved credential and durable checkpoints, without duplicate
   events; exchanges again in the reverse direction after another local record.
5. Checks that credential rotation and revocation survive reopening SQLite.

Boundary tests cover routing, methods, media type, bad credentials, tampered
signatures, declared/chunked oversized bodies and a stalled request body. TLS tests
load PKCS12 material, retain hostname verification and reject an unknown CA,
mismatched hostname, wrong password and missing key/trust material. Temporary
keys and databases are deleted; no user store is changed.

## Hosting a provisioned node

The CLI deliberately requires an existing explicit database. It does not enroll
peers, create actor signing keys or grant authority automatically. Prepare each
store through the governed `P09Runtime.peerProvisioning` / `keyProvisioning` APIs
under an independently authorized local operator (ADR-011). Each peer policy must
bind the remote node to its actors, exact contexts, directions and permitted
origins. Allow reflected local-origin history explicitly if the exchange needs it.
Keep the returned plaintext peer token in the host's secret storage; SQLite retains
only its verifier. Preserve the node ID and local actor configuration on restart.

The CLI profile supports one local Person actor and no state-assertion entities.
A richer host must supply its explicit `LocalSyncIdentity` through the Kotlin API.
New events must already be signed before persistence, for example via SignedP10Runtime.
TLS keys, actor signing keys and peer bearer credentials are separate material.

Create a PKCS12 server key store with a certificate valid for the actual endpoint
hostname. For a loopback-only rehearsal, this example creates a temporary certificate;
its public certificate must be explicitly trusted by the peer's client:

```bash
umask 077
read -r -s -p 'TLS store password: ' SOTA_P09_TLS_PASSWORD
export SOTA_P09_TLS_PASSWORD
keytool -genkeypair -alias p09 -keyalg RSA -keysize 3072 -validity 7 -dname CN=localhost -ext SAN=dns:localhost -storetype PKCS12 -keystore b-server.p12 -storepass:env SOTA_P09_TLS_PASSWORD
keytool -exportcert -rfc -alias p09 -keystore b-server.p12 -storepass:env SOTA_P09_TLS_PASSWORD -file b-public.pem
keytool -importcert -alias node-b -file b-public.pem -storetype PKCS12 -keystore a-trust.p12 -storepass:env SOTA_P09_TLS_PASSWORD
```

Verify the public certificate through your trusted provisioning channel before
accepting it. Generate independent server keys and a reciprocal trust store for
node A when it will receive exchanges. No trust-all SSL context is supported.

On node B, with its provisioned store and TLS password in the environment:

```bash
./gradlew :api:run --args='sync serve --db /path/b.db --node node-b --actor person-b --governance-context peer-governance --keystore /path/b-server.p12 --port 8443'
```

The listener binds `127.0.0.1` by default. `--bind ADDRESS` is explicit for other
interfaces. Only `POST /p09` with JSON is accepted. Stop with Ctrl+C; worker and
deadline executors stop before the CLI closes SQLite.

On node A, supply the token issued by B for A, never B's token for the reverse direction:

```bash
read -r -s -p 'Peer token: ' SOTA_P09_PEER_TOKEN
export SOTA_P09_PEER_TOKEN
./gradlew :api:run --args='sync once --db /path/a.db --node node-a --actor person-a --governance-context peer-governance --peer node-b --endpoint https://localhost:8443/p09 --truststore /path/a-trust.p12'
unset SOTA_P09_PEER_TOKEN SOTA_P09_TLS_PASSWORD
```

Use the peer's DNS name when nodes are on separate machines; it must match the
certificate. `once` exchanges one page (at most 256 records), prints only cursors,
and exits. Repeat until cursors stop advancing. Preserve both databases, the trust
configuration and secret material across restarts. Failed or lost responses can
be retried; they do not advance the sender checkpoint. The receiver may already
have committed, and exact replay is safe.

## Hosting boundaries

`P09HttpsServer(address, tlsContext, runtime)` is a bounded JDK HTTPS adapter. It
serializes runtime calls with one worker, bounds its queue to eight tasks and
limits a request body to 4 MiB, including chunked requests. A configurable deadline
(default 15 seconds) closes stalled handled exchanges. Responses remain bounded by
P09Runtime; the client already enforces response size and completion timeout.
HTTP failures expose generic messages, not credentials or exception details.
An oversized streaming upload may observe connection termination instead of 413
when the server rejects it before the client finishes writing; no batch is admitted.

Give the host exclusive use of its runtime/store while running. In this pilot,
stop the local listener before using that same store for CLI production or outbound
sync, and restart it afterwards. A concurrent host scheduler and multi-process
writer coordination have not been added. The automated pilot switches directions
between listener lifetimes and retains both stores.

The handler deadline starts after request dispatch. It is not a TLS handshake,
header, idle-connection or queued-connection deadline. Public hosting needs those
limits, connection/rate limits and operational supervision at the network edge.
There is no automatic certificate renewal, secret distribution, retry scheduler,
health/metrics endpoint or Internet deployment in this change. Cross-node trust
for previously unseen signatures from retired actor keys remains a separate
ADR-012 follow-up; a TLS connection does not solve that historical trust question.

## Verification result — 2026-09-30

Full JDK 21 build, Detekt and migration verification passed: 154 tests, zero
failures/errors/skips. A separate smoke run of the assembled CLI on a temporary
SQLite database verified PKCS12 password loading from the environment, HTTPS 401
and 405 responses, SIGTERM shutdown, restart on the same port and no journal writes
from rejected requests. No real user database or credential was used.
