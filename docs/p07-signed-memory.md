# Signed P07 Experience and Knowledge

`api.memory.SignedP07Runtime` implements `MemoryKnowledgeProtocol` for a trusted
local host. Construct it with the shared `SqlDelightStore`, `LocalSyncIdentity`,
governance context, clock, IDs, actor-bound signer and optional RightsConstraint.
The host authenticates the invocation actor and protects private signing keys.
Public actor keys must already be provisioned through governed P09 operations.
`runtime.sync` exposes the existing strict P09 composition.

## Facts and sources

| Operation | Signed fact | Causal parents |
|---|---|---|
| createExperience | EXPERIENCE_RECORDED | Every supplied source Event |
| proposeKnowledgeCandidate | KNOWLEDGE_PROPOSED | Exact source Experience fact |
| reviseKnowledge | KNOWLEDGE_REVISED | Exact predecessor Knowledge fact and source Experience fact |
| validateKnowledge | KNOWLEDGE_VALIDATED | Current candidate fact |
| deprecate | KNOWLEDGE_DEPRECATED | Current candidate or validated fact |

Sources must equal their persisted entities and have exact, locally verified,
canonical signed records from this node. Events must be nonempty, distinct,
share a context and not come from the future. Experience/Knowledge sources must
match the complete snapshot in their signed history. Forged input, stale states,
directly replaced database snapshots, foreign origins, unsigned legacy data and
unverified journal entries cannot be promoted into signed memory history.

Each fact carries `entityId`, `snapshot`, invocation `purpose` and nullable `reason`.
`snapshot` is the complete version-1 JSON string from `JsonMemorySnapshotCodec`,
including all domain fields and original entity provenance. The surrounding P09
signature binds those exact bytes, context, origin and causal parents. The Event's
actor/provenance identify the person or collective making this transition; a
validator does not replace the Knowledge's original author. Deprecation requires
a nonblank reason, as does revision. These facts have no state assertions.

## Governance and atomicity

[ADR-026](adr/ADR-026-knowledge-version-lineage.md) adds
`reviseKnowledge(invocation, previous, from, statement, reason)`. It creates a new
CANDIDATE with the next version number and immutable predecessor link, while
retaining the predecessor's content and status. Both sources must belong to the
invoker and share a context. Changed content and an exact nonblank reason are
required. Revision accepts CANDIDATE, VALIDATED or DEPRECATED predecessors, rejects
stale inputs and existing local successors, and never copies validation to the child.
Repository writes preserve content/provenance/version links; only status can change
on an existing ID. General branching and validation mandates remain separate work.

The shared MemoryService now also enforces exact persisted sources and the bounded
candidate/validated/deprecated lifecycle in ordinary mode under
[ADR-023](adr/ADR-023-persisted-memory-boundary.md). It requires an EventStore and
uses a shared repository transaction. Ordinary mode accepts local unsigned facts;
the signed checks described above remain additional requirements.

[ADR-025](adr/ADR-025-local-memory-audit.md) adds a shared typed audit hook.
Ordinary MemoryService writes unsigned LOCAL_* Events with before/after snapshots
and reasons in the entity transaction. SignedP07Runtime injects SignedMemoryAudit
and writes only signed facts, without duplicate unsigned Events. The snapshot and
signed payload formats remain unchanged. Sources are checked before service policy evaluation and rechecked before
the signed fact is appended. The entity write follows audit inside the same transaction.
Revision uses that same hook with a new fact type and two verified causal parents;
the existing snapshot and signed payload formats remain unchanged.

The existing MemoryService rules remain: Experience derives from the invoker's
own Events, proposals belong to the Experience author, validation requires a
matching non-Agent validator, and deprecation belongs to the Knowledge author.
RightsConstraint uses the persisted source context. The signed runtime additionally
rejects nonlocal actors and Agents even with a permissive rights policy. It allows
validation only from CANDIDATE and deprecation only from CANDIDATE or VALIDATED.
This is the existing MVP governance boundary; general collective mandates and
command-specific authorization remain roadmap work. A caller-supplied SubjectRef
alone is not authentication.

Each operation holds the shared store gate and one SQLite transaction for the
entity mutation, Event, P09 journal and exact verified-key receipt. Any failure,
including signing or receipt insertion, rolls everything back. Other users of the
same SQLite connection must use the store gate. Signer providers run inside that
transaction and should use locally available keys.

Every new fact requires the actor's current provisioned key. Retired keys may
verify exact source records already backed by local receipts; this does not permit
old keys to sign new facts. Independent receiving nodes still apply current peer
policy and their own historical evidence rules. This increment adds no automatic
historical approval or command idempotency. Generated Knowledge ID collisions are
rejected before save, including in the ordinary MemoryService.

## Delivery and limits

Receiving P07 history through P09 neither materializes remote Experience/Knowledge
entities nor validates knowledge, executes Actions or grants authority. Research
remains a separate candidate-only journal. The ordinary unsigned `demo` remains
supported with persisted sources; the unsupported generic `recordEvent` entry
point remains as before. No new P07 CLI
command, remote dispatcher, unsigned-history upgrade or schema migration is added;
Experience and successor lookups query the existing tables.

`SignedMemoryTest`, `SignedMemorySafetyTest` and `SignedMemoryAtomicityTest` cover
the full lifecycle, source/provenance checks, validator identity, no-Agent boundary,
current keys and historical receipts, restart, rollback at every write stage,
lost acknowledgement and duplicate-free exchange. The [HTTPS rehearsal](getting-started.md)
now exchanges ten signed records: Action, Result, Experience, Knowledge proposal
and six exit stages. See [ADR-019](adr/ADR-019-signed-memory-producers.md).
KnowledgeLineageTest, KnowledgeRevisionTest and KnowledgeRevisionBoundaryTest add
revision/immutability coverage under ADR-026. The signed-memory recovery test includes
a new version and its independent validation in its seven-record exchange.
