# Running a P09 node with background synchronization

`sync run` keeps HTTPS receiving and outbound retries active in one process. Use
already provisioned SQLite, peer credentials and TLS stores from the
[HTTPS setup guide](p09-https-pilot.md). No new trust is created by starting a loop.

```bash
./gradlew :api:run --args='sync run --db /path/a.db --node node-a --actor person-a --governance-context peer-governance --keystore /path/a-server.p12 --port 8443 --peer node-b --endpoint https://node-b.example:8443/p09 --truststore /path/a-trust.p12 --interval-seconds 5 --max-backoff-seconds 60'
```

Set `SOTA_P09_TLS_PASSWORD` for the PKCS12 stores and `SOTA_P09_PEER_TOKEN` to the
credential issued by B for A. Do not place secrets in command-line arguments.
Configure B reciprocally with its own node identity, actor, stores and A-issued
token. The endpoint hostname must match its certificate. The listener defaults to
loopback; select `--bind ADDRESS` explicitly for another interface.

Defaults: one outbound page per attempt, 100 ms after progress, 5 seconds when idle,
and retries after 1, 2, 4, 8, 16, 32, then at most 60 seconds following consecutive
failures. Success resets failure backoff. Large journals drain in bounded pages;
failed or rejected batches never advance the local acknowledgement cursor. No
records are silently skipped to unblock a batch. One slow peer delays outbound
work until transport timeout; incoming requests remain able to access local state.

Stop with Ctrl+C or SIGTERM. The loop cancels its current outgoing request and
stops scheduling, then the listener closes. HTTP client and SQLite cleanup finish
before the JVM shutdown hook returns (or its outer 60-second deadline expires).
Restart the same command with the same database, node/actor IDs and credentials.
Retry counters reset, while journal/checkpoints survive. A remote commit followed
by a lost response is recovered through normal exact replay.

## Host API and ownership

```kotlin
HttpClient.newBuilder().sslContext(clientTls).build().use { client ->
    val transport = HttpsSyncTransport(endpoints, JsonSyncMessageCodec(), client, credentials)
    P09NodeHost(address, serverTls, runtime, endpoints.keys, transport).use { host ->
        // Keep the owning SqlDelightStore open for the entire host lifetime.
        // host.snapshot() returns per-peer attempts, consecutive failures and last successful checkpoint.
        waitForApplicationStop()
    }
}
```

The API accepts up to 64 peers; the CLI currently configures one. Each peer has one
scheduled task, and one worker performs outbound exchanges. Operational snapshots
are in memory and are not durable cursors. A nonzero
failure count can reflect unavailable transport, rejected credentials/policy,
a stale concurrent response or another failed exchange. Correct the underlying
configuration through the governed local APIs; retries never relax admission.

P09Runtime coordinates inbound/outbound phases, recording and provisioning through
a shared `SqlDelightStore.withLocalAccess` gate. Several runtime instances on the
**same store object** share it. Other application operations must use that same gate
around their complete local transaction:

```kotlin
store.withLocalAccess {
    // For example, the entire signed P10 exit step, not only its event recorder.
    signedExit.exit.service.revokeActiveDelegations(invocation, exitId)
}
```

Never put `synchronize`, a network call or an unbounded wait inside this block.
Raw repositories and separate processes/store instances are outside the gate.
Do not close SQLite or the transport until the host has stopped. Custom transports
must be interruptible or bounded; the built-in HTTPS transport has a complete-request
timeout and cancels its pending request on interruption.

Public connection/header/handshake limits, metrics endpoints, live configuration
reload and automatic TLS/credential distribution are not part of this scheduler.
See [ADR-017](adr/ADR-017-background-sync-lifecycle.md) for concurrency and shutdown
semantics.

## Verification — 2026-09-30

Full JDK 21 build, Detekt and migration verification passed: 178 tests, zero
failures/errors/skips, including eight background/concurrency/lifecycle tests.
An assembled-CLI smoke run used temporary SQLite and TLS material to verify
self-sync startup rejection and cleanup, HTTPS 401/405 responses while `sync run`
was active, SIGTERM cleanup, same-port restart and no rejected-request journal writes.

## Operational diagnostics

`host.snapshot()` (also `loop.snapshot()`) returns an immutable per-peer value:

- `phase`: WAITING, RUNNING, BACKOFF or STOPPED after successful close;
- `attempts`: started attempts, including interrupted attempts;
- `consecutiveFailures`: completed consecutive failures, capped at 30;
- `lastAttemptAt`, `lastSuccessAt`, `nextAttemptAt`: UTC observations;
- `lastCheckpoint`: last successful outbound exchange checkpoint;
- `failure`: TLS, TIMEOUT, NETWORK, HTTP_REJECTED, LOCAL_VALIDATION or UNKNOWN.

Failures retain the last successful checkpoint and success time. A successful
exchange clears the failure category and resets backoff. RUNNING has no next-attempt
time; BACKOFF and WAITING show an estimated scheduled time. A busy single worker
may run later, and wall-clock adjustments can affect displayed times. STOPPED
clears the schedule. Shutdown interruption is not recorded as a peer failure.

Categories are conservative: LOCAL_VALIDATION means an IllegalArgumentException,
not proof of a specific policy denial. UNKNOWN includes unclassified local errors.
HTTP_REJECTED does not distinguish bad credentials from remote server failures.
Diagnose these through authorized local configuration and server operations;
never loosen admission automatically. Wrapped async TLS/network failures are
classified without copying exception messages, server bodies, URLs or credentials.

This is a local host API. No network status endpoint or periodic CLI status output
is exposed. Snapshots reset on restart and do not establish inbound health,
remote availability, or complete convergence. SQLite remains authoritative.

Verification — 2026-10-01: 185 tests pass, including seven diagnostic cases and
recovery assertions in the existing HTTPS host test.
