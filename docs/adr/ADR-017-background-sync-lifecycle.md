# ADR-017: Background P09 sync and node lifecycle

Status: Accepted — 2026-09-30

## Context

The HTTPS pilot required switching between receiving and outbound exchange. Holding
a single SQLite connection lock across a network call would make two nodes waiting
on each other deadlock. Concurrently entering that same connection without a local
coordination boundary is also unsafe. P09 already has durable cursors and stale
response checks, which retries must preserve.

## Decision

Split SyncService's outgoing exchange into trusted `prepareExchange` and
`completeExchange` phases. Its existing synchronous API remains available. The
production runtime uses a store-owned, reentrant, interruptible local-access gate
for each phase, incoming exchange, local recording and provisioning transactions.
No store lock or database transaction spans the network call. Runtime instances
sharing the same SqlDelightStore share the gate.

Inbound work may advance a checkpoint while an outbound request is in flight.
Completion still compares both cursor positions with its prepared request. A stale
response fails without overwriting the newer cursor; the background worker retries
from the current durable state. Signature, peer, history-approval and sharing checks
are unchanged and are repeated through the normal runtime path.

Add P09SyncLoop with one outbound worker and at most one scheduled task per
configured peer (maximum 64). An attempt exchanges one normal bounded P09 page.
Checkpoint progress schedules the next page after a short delay; no progress uses
the idle interval. Failure uses capped exponential backoff. Failed peers do not
suppress attempts for other peers. A slow peer can delay the single outbound worker
until transport timeout, but cannot hold the inbound store gate during network I/O.

Operational snapshots contain attempts, consecutive failures and the last successful
checkpoint. They are in memory and contain no exception text or credentials. The
SQLite checkpoint is authoritative. Restart resets retry timing/counters and resumes
from SQLite; it does not persist a second queue or mark failed batches acknowledged.

P09NodeHost owns the HTTPS listener and loop. Shutdown cancels/interrupts outgoing
work, waits for its worker, then stops the listener. The caller closes HttpClient
and SQLite afterwards. Custom transports must support bounded completion or
interruption; close reports failure if its worker cannot stop. In the CLI, the JVM
shutdown hook signals the main thread and waits for resource use-blocks to finish,
with a 60-second outer deadline.

`sync run` composes one configured outbound peer with the listener in one process.
The Kotlin API supports multiple peers. CLI credentials remain environment-supplied;
no automatic enrollment, self-provisioning or policy relaxation is introduced.

## Local access contract and limits

The host owns its SqlDelightStore until all workers stop. P09Runtime's exchange,
synchronize, recordLocal and governed provisioning entry points use the gate.
Other local application commands sharing that store must wrap their **entire**
local operation, including outer transactions, in `store.withLocalAccess { ... }`.
Do not wrap a network operation in that block. For example, a host integrating
SignedP10Runtime must gate the complete exit operation, not just its final recorder.
Raw repository access and separately opened stores/processes do not share this gate.
Multi-process hosting and a general application-wide command dispatcher are outside
this change.

Backoff is capped exponential without jitter; no external retry coordinator or
adaptive rate control is added. The CLI does not expose a remote status endpoint.
Snapshots are for host integrations; full operational metrics, live config reload,
secret rotation delivery, public connection controls and certificate renewal remain
separate work. Existing HTTPS handshake/header/connection limits are unchanged.

## Evidence

P09NodeHostTest runs two real HTTPS hosts with simultaneous background exchanges,
adds a local fact while running, verifies pagination beyond 256 records, recovers
an offline peer and reopens both stores after a lost acknowledgement. A failing
untrusted peer cannot starve a valid peer. SyncConcurrencyTest blocks an outgoing
network call while another runtime on the same store receives data, then proves a
stale response cannot regress the checkpoint. SyncLoopLifecycleTest verifies bounded
backoff, fail-fast settings, revoked-peer denial and interrupted shutdown with no
further scheduled attempts.
