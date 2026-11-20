# ADR-023 — Persisted sources and current states in ordinary P07

Date: 2026-11-20
Status: Accepted for the bounded local MVP slice

## Source and gap

Protocol Architecture v0.1 §10 (PDF p.10) distinguishes Event, Experience,
Knowledge and canonical Knowledge. Memory & Knowledge Architecture v0.1 §9
(PDF pp.6–7) requires provenance; §10 (p.7) distinguishes candidate, validated
and canonical states; §27 (pp.16–17) addresses history integrity; §43 M-02/M-03
(p.25) requires traceable Experience and Knowledge provenance. These are
paraphrases, not a complete collective validation or canonicalization protocol.

Source SHA-256 fingerprints:

- Protocol Architecture: `7bfb4bcf222e74b2f6b956b9ea11667692e10349aa7e8f6236888e25bad61e41`.
- Memory & Knowledge Architecture: `1818e8fd7265ac3c8f222d9df39d479fb03506729f081a61e9ba3cb9fcabe079`.

ADR-019 checks exact sources and current states in SignedP07Runtime. Ordinary
MemoryService previously trusted caller-supplied Event/Experience/Knowledge
objects. Foreign keys could reject some missing references only during writes,
but did not validate source contents. Stale Knowledge could replace newer states,
and a failure after a repository write could leave a partial operation committed.
Eight of nine new regression tests failed against that implementation.

## Decision

MemoryService now requires an EventStore from the same store as its Experience
and Knowledge repositories. Every mutating memory operation runs in the mandatory
KnowledgeRepository transaction, including source reads, rights evaluation and
the write. SQLite uses transactionWithResult; the signed wrapper's outer transaction
continues to include Event/journal/receipt writes. Hosts sharing a SQLite connection
must hold the existing SqlDelightStore local-access gate for the whole operation.
The transaction port does not provide a distributed transaction across stores.

- Experience sources must be nonempty, distinct, in one context, authored by the
  invoking subject, equal to their persisted Events and no later than the injected
  clock. Experience ID collisions are rejected before insertion.
- A Knowledge proposal requires exact equality with the persisted Experience;
  the existing author check and new-ID guard still apply.
- Validation requires exact current persisted Knowledge in CANDIDATE state,
  the matching non-Agent validator and rights approval.
- Deprecation requires exact current persisted Knowledge, its original author,
  CANDIDATE or VALIDATED state, a nonblank reason and rights approval.

These are ADR-019's bounded source/transition rules applied to the shared service.
Policy context comes from the checked source, not the caller's invocation context.
Invalid inputs are rejected before policy evaluation or writes. Replaying old
objects after validation/deprecation or database reopen cannot restore an old state.

## Compatibility and limits

All CLI, signed-runtime and test compositions inject the EventStore explicitly;
there is no permissive fallback for omitted storage. Custom KnowledgeRepository
implementations must provide transactions. No schema or stored-data migration is
required. Callers that previously invented source objects must first persist them
through the appropriate trusted producer.

Ordinary local unsigned Events remain accepted. Equality with local storage is
not cryptographic proof, proof of real-world truth, or a recursive audit of imported
Experience ancestry. Raw repositories remain trusted persistence adapters, not
authenticated command APIs. SignedP07Runtime retains its additional canonical
snapshot, signature, local-origin, current-key and verification-receipt checks.

Ordinary P07 still does not emit lifecycle audit Events or persist a deprecation
reason; the reason is required input only. Signed P07 records it in its signed fact.
General validation mandates, human-proof hosting, canonicalization, new versions,
full unsigned lifecycle audit and remote projection remain separate work. This
increment does not mark M-05/M-10 or all P07 conformance complete.

## Evidence

MemorySourceBoundaryTest adds nine SQLite-backed tests: source equality, invalid
Event sets, forged author/content, every Knowledge state, stale objects after
reopen, ordinary unsigned lifecycle, persisted policy context, and rollback after
Experience insert or Knowledge proposal/validation/deprecation writes. Existing
no-Agent fixtures now persist their source Experience so they exercise the intended
validator boundary. SignedMemory tests and the two-node rehearsal retain the
stronger signed contract.
