# ADR-015: Bounded HTTPS hosting for the production P09 pilot

Status: Accepted — 2026-09-30

## Context

P09 already had a strict production composition root, durable recovery and a
bounded HTTPS client. Existing HTTPS tests used a fixture endpoint rather than
SQLite peer authentication and production signature/origin policy. There was no
reusable listener or command for connecting provisioned nodes.

## Decision

Add a JDK `HttpsServer` adapter in api and explicit `sync serve` / `sync once`
commands. Reuse `P09Runtime.exchange`; no sender-text authentication, permissive
admission profile or remote provisioning route is introduced. Invalid credentials
have a typed exception so the host can return 401 without exposing error details.
Other invalid input receives 400; unexpected internal failures receive a generic
500. Routing/method/media/size failures are rejected before protocol dispatch.

Server keys and client trust anchors load from explicit PKCS12 files. TLS 1.2/1.3
and normal certificate/hostname validation remain enabled. Credentials and store
passwords are supplied by the local host environment, never command-line options
or printed output. Actor public keys and peer policies continue to load from SQLite;
private actor signing keys remain producer-owned.

Bound body reads to 4 MiB plus one detection byte, including chunked bodies. Use
one worker and a bounded queue; close handled exchanges after a deadline. Shutdown
stops the listener and executors before closing its store. This pilot grants the
listener exclusive use of its runtime/store; it does not add concurrent local
producers or an outbound scheduler in this ADR. [ADR-017](ADR-017-background-sync-lifecycle.md)
later adds shared local access, combined hosting and background retries.

## Evidence and limits

HttpsProductionPilotTest exchanges signed records over real TLS between two SQLite
nodes, loses a response after remote commit, reopens both stores and retries without
duplication. It also verifies durable credential rotation/revocation. Boundary and
TLS tests cover malformed/tampered requests, body limits, stalled bodies, certificate
trust, hostname checks and invalid TLS configuration.

See the [runbook](../p09-https-pilot.md). The body deadline is not a pre-dispatch
connection or handshake limit; public hosting still needs edge controls and process
supervision. No automatic enrollment, key distribution, general remote command
execution or cross-node historical-key authorization is inferred from TLS.
