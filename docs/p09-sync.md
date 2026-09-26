# P09 synchronization slice

Implements the reconciliation and conflict-preservation model in ADR-004,
with JSON and an HTTPS/REST client adapter per ADR-005. This is an event
exchange service, not a remote command execution endpoint.

## Composition

- `application/sync/SyncPorts.kt`: transport, codecs, repository,
  per-peer checkpoints, and mandatory import/export admission policy.
- `domain/sync/SyncModel.kt`: immutable origin/causality metadata,
  assertions, conflicts and projections. Entity conflict rules live in
  `domain/relation/AuthorityConflictRule.kt` and
  `domain/memory/MemoryConflictRules.kt`.
- `sync/SyncService.kt`: offline recording, capture of existing local
  events, outgoing exchanges, receiving exchanges and projection reads.
- `persistence/SqlDelightSyncRepository.kt` and `Sync.sq`: durable journal,
  checkpoints and append-only conflict records. `SqlDelightStore` creates
  the new tables/triggers on existing databases through an additive,
  idempotent compatibility migration.
- `protocol/JsonSyncRecordCodec.kt` and `JsonSyncMessageCodec.kt`: P09 JSON
  records and request/response envelopes, versioned independently of HTTP.
- `sync/HttpsSyncTransport.kt`: configured HTTPS endpoints, supplied
  Authorization headers, no redirects, bounded response body and timeout.
- `sync/SyncEndpoint.kt`: server-neutral request handler. Its host must
  supply the authenticated peer identity; the request's sender field is
  checked against that identity, not used to authenticate it.

Construct a `SqlDelightSyncRepository(database, JsonSyncRecordCodec())`,
then a `SyncService(nodeId, repository, codec, admission, ids)`. Supply a
stable local node ID and an explicit `SyncAdmission` implementation.
There is deliberately no default allow-all policy. The permissive policy
in tests applies only to trusted fixtures.

For deployment, compose `ConfiguredPeerAdmission` with
`EventSignatureAdmission`: the former allowlists trusted `SotaId` peers and
can restrict import/export direction, while the latter verifies actor keys.
`HashedBearerPeerCredentialAuthenticator` can resolve an Authorization
Bearer header to a peer identity while retaining only SHA-256 hashes. The
host passes that identity to `SyncEndpoint.exchange`; failed authentication
must stop before request dispatch.

Call `recordLocal(record)` to record an already-authorized local event
with explicit causal parents and, when available, a state assertion.
Call `synchronize(peer, transport)` to exchange one page; repeat while
there are pending journal pages. Existing Core Loop events are captured
as additive events automatically before an exchange. Their missing
entity assertions are not reconstructed from guesses about payloads.

## Merge and durability

A cursor is a contiguous local journal position, not a wall-clock time.
An event's origin and causal parents do not change when another node
forwards it. Each peer tracks acknowledged outgoing and committed
incoming positions separately. A batch contains at most 256 records;
the HTTPS message limit is 4 MiB. Oversized messages are rejected rather
than partially applied; there is no blob/chunk transfer in this slice.

The merge validates every causal parent, rejects cycles, and orders
ready events by ID only for reproducibility. IDs and timestamps never
select a winning state. Identical event replays are harmless. Reusing
an event ID with changed event content, origin or metadata is rejected.
The record codec compares canonical JSON, including sorted object keys.

Event append, journal append, detected conflicts and checkpoint updates
share one SQLite transaction. A failed or lost response leaves the
sender's checkpoint unchanged. Retrying does not duplicate events or
conflicts. A checkpoint-write failure rolls back the received event
rows as well. Offline local recording does not call the transport.

## Entity assertions and projections

`StateAssertion` identifies the entity, exact context, a half-open
validity window `[from, until)`, and a complete normalized attribute map.
It is a claim retained for reconciliation, not an instruction to mutate
authorization or governance state. Producers must supply complete
entity terms, not patches; admission must validate those claims.

- **Authority:** different declared terms for the same authority and
  context conflict when validity windows overlap and neither event is
  a causal ancestor of the other. Terms include issuer, subject, scope,
  basis, accountability and lifecycle state as applicable to the producer.
- **Knowledge:** different declared statements/governance terms of the
  same knowledge entity conflict under the same concurrency, context and
  validity conditions. Sync never validates or canonicalizes knowledge.
- **Experience:** observations are additive and are not collapsed into
  one canonical interpretation.

ADR-004 defines this conflict class between different origins. Equal
claims, different entities/contexts, non-overlapping windows and causal
successors do not create that conflict. The domain rule registry is
closed to unsupported entity kinds; new mutable entities need their own
rule and producer contract before receiving state assertions.

A conflict retains both event IDs; the immutable journal retains their
provenance and authority references. `projection(entity)` recomputes
heads from causal history and returns `CONTESTED` when conflicts exist.
Conflict rows remain historical facts even after later descendants.
Resolution/governance workflows are not implemented in this slice.

SQLite Authority repository reads also expose an existing locally
stored authority as `CONTESTED` when its ID has a sync conflict, excluding
it from active-authority queries. P05 therefore refuses to execute with
that authority. The underlying Authority row is not overwritten and no
remote grant is installed as executable authority. Other received entity
claims remain in the sync read model; arbitrary domain-table replay is
not implemented.

## Admission and deployment boundary

Both exports and imports pass `SyncAdmission`. It must enforce peer
trust, permitted sharing, provenance/origin, event integrity and required
risk-dependent signatures. TLS authenticates the configured endpoint;
it is not proof of the actor or of an event forwarded from another node.
ADR-003a specifies Ed25519. The strict production profile in
[ADR-011](adr/ADR-011-production-peer-admission.md) requires a signed canonical
record digest including sync metadata; it does not upgrade legacy event hashes.
Canonical replay comparison alone is not a signature verifier.

No listener, background sync scheduler, default peer list, peer credential
provisioning or CLI sync command is enabled. The HTTPS client and handler
are exercised by a real local HTTPS server in integration tests. A
production host still needs authentication middleware and its admission
policy. There is no Ktor or new transport-library dependency.

## Verification

Run the full suite, including Detekt, with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build --continue --console=plain
```

The `test/sync` suites cover independent offline logs, conflicts and
causality, JSON round trips, pagination, duplicates and lost replies,
SQLite reopen and upgrade, atomic rollback, peer/admission rejection,
append-only storage, P05 refusal of contested authority, and real
localhost HTTPS success/failure. HTTPS tests generate an ephemeral
localhost certificate with the JDK's `keytool` and delete its temporary
keystore after loading it. No private key is versioned or published.
Only the test client trusts that certificate; hostname verification
remains enabled.

The original MVP Specification §16 was reviewed on 2026-09-26. Exact
AC-12/13/17 criteria, PDF fingerprints and test evidence are recorded in
[the source mapping](ac-p09-mapping.md). AC-17 concerns local Core survival,
not conflict retention. Overall Phase 3 completion is not claimed.

## Production composition (2026-09-26)

Use `api.sync.P09Runtime(store, localIdentity, governanceContext, clock, ids)`.
It composes the SQLite trusted peer registry, current actor keys, bearer
verification, scoped origin/sharing policy and EventSignatureAdmission.
`exchange(authorizationHeader, body)` is the authenticated inbound entry point;
`synchronize(peer, transport)` uses the same admission for outbound exchanges.
`peerProvisioning` and `keyProvisioning` require authenticated local invocations
and explicit delegated authority. See [ADR-011](adr/ADR-011-production-peer-admission.md)
for scope strings, bootstrap boundaries, hashing contract and deployment limits.

`PeerTrust.sq` persists trust policies, credential verifiers and append-only
provisioning audit. SqlDelightStore upgrades older stores additively. Revocation
and rotation affect existing runtimes without process restart. No private signing
keys or plaintext peer secrets are stored in these tables.

Strict admission rejects legacy unsigned local events; producers must create
signed sync-ready records before first persistence. A blocked batch is not
silently filtered or checkpointed. Full historical-key handling and producer
adoption remain open; see [the AC source mapping](ac-p09-mapping.md).
