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

- `phase`: WAITING, RUNNING, BACKOFF or STOPPED after close or an interrupted attempt;
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

This is a local host API. No network status endpoint is exposed. Snapshots reset on restart and do not establish inbound health,
remote availability, or complete convergence. SQLite remains authoritative.

Verification — 2026-10-01: 185 tests pass, including seven diagnostic cases and
recovery assertions in the existing HTTPS host test.

## CLI status output

Add `--status-interval-seconds 5` to the `sync run` command to print a status line
immediately and every five seconds. Accepted values are integer seconds from 1
through 3600. The flag is only valid for `sync run`; omitting it preserves the
startup-only output. It requires no additional credentials or listener.

```text
P09 status: phase=BACKOFF attempts=2 failures=2 reason=NETWORK sent=UNKNOWN received=UNKNOWN lastAttempt=2026-10-01T12:00:00Z lastSuccess=NEVER successes=0 failedTotal=2 cancelled=0 durationNanosTotal=2000000 lastDurationNanos=1000000 nextAttempt=2026-10-01T12:00:02Z
```

The line describes the single configured outbound peer. UNKNOWN checkpoints mean
no successful outbound exchange has been observed in this process, not that the
SQLite checkpoint is zero. `reason` is the safe failure category described above;
NONE means no recorded failure. Times are UTC; nextAttempt is an estimate.

Ctrl+C/SIGTERM wakes the wait immediately, regardless of the status interval.
A final STOPPED line is printed after the loop and listener close successfully.
Output goes to stdout and contains no endpoint URLs, credentials, server response
bodies or exception messages. As with other console output, use a consuming log
sink: blocked stdout can delay the main thread and shutdown cleanup.

Run the assembled CLI smoke check from the repository root with JDK 21:
`python3 scripts/p09-cli-status-smoke.py` after `./gradlew :api:distZip`.
It uses temporary SQLite/TLS fixtures and tests validation, default quiet mode,
periodic status, secret exclusion, long-interval SIGTERM and same-port restart.

CLI verification — 2026-10-01: JDK 21 full build, Detekt, migration checks and
187 tests passed; the assembled CLI status/shutdown smoke check passed.

## Node configuration file

Use `sync run --config /path/node.conf` for a reproducible launch. `sync serve` and
`sync once` also accept `--config` with only the fields relevant to that mode.
The format is UTF-8, at most 64 KiB, with `key=value` lines and `version=1`:

```text
version=1
db=data/node.db
node=node-a
actor=person-a
governance-context=peer-governance
keystore=keys/server.p12
port=8443
peer=node-b
endpoint=https://node-b.example:8443/p09
truststore=keys/trust.p12
interval-seconds=5
max-backoff-seconds=60
status-interval-seconds=5
```

Paths resolve relative to the configuration file's directory. Blank lines and
whole-line `#` comments are allowed; whitespace around keys and values is trimmed.
There are no quotes, escapes, inline comments, includes or environment expansion.
The first `=` separates the key from its value; subsequent `=` characters remain
part of the value. Duplicate, unknown, empty and mode-inapplicable fields fail.
The version and all required fields must be present. Optional defaults match CLI.

`serve` requires db, node, actor, governance-context, keystore and port, with optional
bind. `once` requires db, node, actor, governance-context, peer, endpoint and truststore.
`run` requires both sets and permits bind, the three interval fields shown above,
and optional `metrics-format=json` when a status interval is configured.
Ports are 1–65535; timer values are whole seconds from 1–3600. Self-peer settings and
non-HTTPS endpoints or embedded URL credentials are rejected before opening SQLite.

Do not combine `--config` with `--db` or any other sync option. This intentionally
has no implicit override precedence. The file is read once at startup; stop, edit
and restart to apply changes. It does not provision identity, peers, keys or trust.

Keep `SOTA_P09_TLS_PASSWORD` and `SOTA_P09_PEER_TOKEN` in the process environment.
The file format has no password/token fields. Protect the configuration as trusted
local input: it controls database, identity, network binding and peer destination.
The default bind remains loopback. Live reload, certificate renewal, secret delivery
and inbound metrics or external metric export remain separate work.

Configuration verification — 2026-10-01: full JDK 21 build, Detekt and migrations
passed; 196 tests passed with no failures/errors/skips. Extended CLI smoke passed.

## Outbound operational metrics

Each `PeerSyncStatus.metrics` snapshot now contains process-local cumulative counts
of `successes`, `failures` and `cancellations`, plus `failuresByCategory` using only
the fixed `SyncFailure` enum. A successful exchange resets consecutive failure
backoff but preserves cumulative failure counts. Interrupted attempts count as
cancellations, not peer failures. Retained snapshots do not change as retries run.

`totalDurationNanos` sums completed attempt durations; `lastDurationNanos` is null
until an attempt completes. Both use `System.nanoTime`, independent of wall-clock
adjustments. Duration includes local validation, store-gate waits and transport;
it is not pure network latency. In-flight time is not added until completion.
An interrupted attempt records its elapsed duration and stops that peer's loop.

The `attempts` field counts starts. During an active attempt it is one greater than
successes + failures + cancellations; after normal stop those values balance.
Metrics reset when a new loop starts and are never persisted as sync checkpoints.
No identifiers, exception messages, credentials or payloads become metric labels.

CLI status output includes `successes`, `failedTotal`, `cancelled`,
`durationNanosTotal` and `lastDurationNanos` before `nextAttempt`. Enable it with the
existing `--status-interval-seconds` flag or its configuration-file field. The
existing `failures` output field remains the consecutive failure count.

These counters cover background outbound attempts only. For inbound metrics and
external export see below. Byte/record transfer counts, latency histograms and a
public metrics endpoint remain outside this implementation. `SyncMetricsTest` checks failure/recovery, interruption,
count balance, retained snapshots and reset on a fresh loop.

Metrics verification — 2026-10-02: JDK 21 build, Detekt, migrations and 198 tests
passed with no failures/errors/skips. CLI status/configuration/shutdown smoke passed.

## Inbound observations and external JSON export

`server.inboundMetrics()` and `host.inboundMetrics()` return handler-level snapshots:
started requests, completed handling, in-flight count, aborted handling, response
counts by HTTP status and monotonic duration totals/last duration. A completed
handler has either a fully written response or an aborted exchange; therefore
`completed = sum(responses) + aborted` and `inFlight = started - completed`.

Response counters mean that response writing completed locally, not that the peer
received it or acknowledged a transaction. A journal commit followed by a lost
response can be counted as aborted. Deadline/disconnect exceptions and other
unfinished responses share this category. TLS handshake failures, rejected queued
connections and time before handler dispatch are not included. Counts reset when
the server is recreated. No request-derived labels or response bodies are stored.

For an external collector, add `--metrics-format json --status-interval-seconds 5`
to `sync run`, or set both fields in its configuration file. JSON format requires
`sync run` and a status interval. Each stdout line is a complete version-1 JSON
sample, including the final sample after normal host closure. Startup messages go
to stderr. For collection use the assembled application's launcher directly;
Gradle's own output from `:api:run` is not part of this JSON stream.

The top-level fields are `version`, `inbound`, and `outbound`. Inbound includes
`started`, `completed`, `inFlight`, `aborted`, `durationNanosTotal`, and a `responses`
object keyed by observed HTTP codes. Outbound aggregates configured peers into
`configuredPeers`, `attempts`, `successes`, `failures`, `cancellations`,
`durationNanosTotal`, `phases`, and `failuresByCategory`. All counts and durations
are integers; durations are nanoseconds. Response codes absent from a sample have
zero observations. Phase and failure-category keys are fixed enums.

Samples contain no peer/node/actor IDs, endpoints, credentials or request content.
Inbound values form a coherent snapshot; inbound and outbound are sampled
separately, not as a distributed or cross-direction transaction. A collector may
add its own trusted instance labels and scrape time. It must account for counter
reset after restart. Output is synchronous; a collector must keep consuming
stdout so it cannot block process cleanup. No new network listener is opened.

`P09HttpsBoundaryTest` verifies response categories, preserved snapshots and an
aborted stalled body followed by recovery. `NodeMetricsJsonTest` checks the versioned
numeric schema and aggregation. CLI smoke parses every JSON stdout line, checks
401/405 counters, final STOPPED state, configuration loading and secret exclusion.

Inbound/export verification — 2026-10-05: full JDK 21 build, Detekt, migrations
and 200 tests passed with no failures/errors/skips. JSON CLI smoke passed.
