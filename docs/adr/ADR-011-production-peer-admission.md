# ADR-011 — Production P09 admission and provisioning boundary

Status: IMPLEMENTED profile; source mapping and remaining gaps in docs/ac-p09-mapping.md
Date: 2026-09-26

## Decision

`api.sync.P09Runtime` composes SQLite peer trust, hashed bearer authentication,
`ProductionPeerAdmission`, `ActorKeyDirectory.fromRecords`,
`EventSignatureAdmission`, the JSON codecs and `SyncService`.
No trust-on-first-use or permissive admission fallback exists. Empty registry
means no peer can exchange data. The existing CLI remains local; this change
introduces a callable composition root, not a network listener.

A peer policy contains exact contexts, its origin-author bindings, allowed
IMPORT/EXPORT directions, explicit source origins in each direction, and
explicit entity IDs/kinds for assertions. A trusted relay is not automatically
a trusted origin: the origin must also remain active in the registry. The local
origin uses the separately supplied `LocalSyncIdentity`. An empty assertion
allowlist allows additive events only. A batch is rejected atomically if any
record falls outside the sharing policy; records are never silently filtered
because that would change contiguous cursor semantics.

All production-profile records require a trusted Ed25519 actor signature,
including records whose actor key has been revoked or is unknown (they are
rejected, never downgraded to unsigned R0/R1). Actor, provenance author and
origin binding must agree; provenance time must equal event time, and a source
event reference must be an explicit causal parent. SyncService additionally
checks parent existence, cycles, immutable replay and conflicting assertions.
The registry governs attribution and sharing, not executable remote authority.

## Signed record integrity profile

ADR-003a still defines Ed25519 over UTF-8 lowercase SHA-256 `Event.contentHash`.
The strict P09 profile defines how that hash is computed before the first
persistence of a sync-ready event:

1. Copy the complete SyncRecord with event contentHash set to the empty string
   and event signature set to null.
2. Encode with JsonSyncRecordCodec (canonical sorted maps and parent IDs).
3. Prefix `SOTA-P09-SIGNED-RECORD-v1` followed by a newline.
4. SHA-256 the UTF-8 bytes, then sign the resulting lowercase hex as in ADR-003a.

This explicit profile binds payload, authority/decision/result references,
provenance, origin, parents and assertion together. Signing an unverified hash
provided by the sender would not establish integrity. The transport JSON
version remains 1; admission selects this strict profile explicitly.

`SyncRecordIntegrity.sign` is for producers BEFORE Event append. It does not
upgrade, mutate or re-sign immutable legacy history. Existing unsigned or
legacy-hash Core Loop events remain locally readable but fail this production
admission. Full producer adoption and a separately specified historical-key
verification/migration policy remain open. Active-key rotation/revocation is
fail-closed: old-key records no longer pass new admissions, while already
stored history is retained. There is no historical-key fallback.

## Provisioning governance

Local authenticated adapters supply ProtocolInvocation to provisioning services.
P09 bearer identity never grants provisioning rights. Each successful operation
requires an existing ACTIVE, time-valid, independently delegated Authority:

| Operation | Scope action | Explicit scope resource |
|---|---|---|
| Peer enrollment | peer.enroll | peer:<SotaId> |
| Credential rotation | peer.rotate | peer:<SotaId> |
| Peer revocation | peer.revoke | peer:<SotaId> |
| Actor key enrollment | key.enroll | key:<PERSON/CORE/SOTA>:<id> |
| Actor key rotation | key.rotate | key:<PERSON/CORE/SOTA>:<id> |
| Actor key revocation | key.revoke | key:<PERSON/CORE/SOTA>:<id> |

The authority subject must match the caller, its context must equal the
configured governance context, and its issuer must differ from the caller.
An empty resource scope is deliberately insufficient for provisioning.
RightsConstraint is evaluated before any mutation; a denial overrides valid authority.
Agents and self-provisioning are rejected; a peer operator cannot be one of
that peer's registered actors or its Sota subject. Key operators cannot be the
key subject. Initial operator authorities are a separate trusted local bootstrap,
not an authority that enrollment can create for itself.

Peer enroll creates a new identity once; revoked identities remain tombstoned.
Rotate requires the current peer revision, replaces the verifier and invalidates
the old secret. Revoke increments the revision and disables trust immediately.
Changing sharing policy is not exposed as an unaudited update command.
Key rotate/revoke require the expected current key ID; key IDs cannot be reused.
Malformed public keys are rejected before persistence.

Secrets are generated using SecureRandom (256 bits), returned once, and only
SHA-256 verifiers are stored. The registry authenticates inbound credentials;
remote-issued outbound secrets must be supplied by the host to HTTPS transport.
There is no default shared token or secret committed to configuration.

SQLite transactions couple mutation and append-only audit with operator,
authority reference, operation, target, purpose and timestamp. Existing key audit
also retains key material and lifecycle operations. Failed mutations leave no
partial key/credential change or success audit. Repository ports are trusted
infrastructure; they must not be exposed as unauthenticated provisioning APIs.

## Composition and deployment

The inbound runtime transaction includes credential lookup, current policy/key
loading, signature checks, merge and checkpoint commit. Policy and keys are read
from SQLite on each admission, so an already-created runtime sees rotation and
revocation. Outbound admissions use fresh transactional snapshots before send
and after response; network I/O does not hold a database transaction open.

The host must authenticate local operators before calling provisioning, protect
SQLite access, terminate TLS, bound HTTP input, keep its stable local node ID and
supply outbound peer credentials. Production listener/scheduler and CLI commands
are not introduced here. `api -> sync` is the additional composition dependency;
Domain/Application still do not depend on infrastructure.

## Verification and remaining criteria

`test/p09` exercises the real SQLite/API composition: signed exchanges and replay,
relay policy, provenance/content/metadata tampering, credential and key lifecycle,
unauthorized provisioning, restart, additive upgrade, audit immutability and
transaction rollback. Existing causal/conflict/HTTPS tests continue to run.
See `docs/ac-p09-mapping.md` for exact source criteria, automated evidence and
remaining deployment/legacy-producer limitations.
