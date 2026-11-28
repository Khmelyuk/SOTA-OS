# Core Genesis Mandate — migration, recovery and evidence plan

Status: **PROPOSED candidate, no production migration**. Date: 2026-11-28.
See [ADR-032](adr/ADR-032-core-genesis-mandate.md), [open decisions](core-genesis-mandate-questions.md)
and [acceptance matrix](core-genesis-mandate-acceptance.md).

## Schema 1 → 2 candidate

Keep frozen `1.db`, current `.sq`, runtime installer, version marker and accepted
schema catalog unchanged until the ADR and implementation scope are approved.
Do not add a placeholder `.sqm`, raise `user_version`, or seed illustrative mandates
in production. The existing numbered dispatcher is the future execution mechanism,
not evidence that v2 already exists.

Candidate persistence responsibilities:

| Logical group | Required constraints to review before DDL |
|---|---|
| Agreement / revision / defined roster | Immutable exact terms/digest and unique subject roster; nonempty roster; explicit collective/holder mapping; revision/predecessor binding |
| Source entitlement / delegation edge | Source subject and evidence, exact actions/resources/time, nontransferable rights constraints, explicit redelegation ceiling; no circular or invented parent |
| Founding consent / approval | Exact proposal, signer/representative/key, purpose, epoch, nonce and validity; unique consumption per bound decision; identical retries idempotent; immutable evidence |
| Mandate / scope / quorum policy | Explicit holder, context and source-to-power edges; finite interval; electorate policy and immutable decision snapshot; no default active or wildcard authority |
| Revocation / dispute / audit facts | Append-only causal statements; issuer can withdraw own source; verified historical signatures retained; current projection reconstructible, not sole authority evidence |
| Legacy basis linkage | Missing verified basis is explicit unresolved provenance, never a generated founder vote or a new live grant |

These may share tables or use events plus projections after review. They are not
approved SQL names. Specify all foreign keys, uniqueness, indexes, append-only guards,
cycle prevention and canonical encodings with actual DDL before implementation.

Data conversion and governance activation must remain separate. For every existing
Authority/Consent/Membership/Event, retain original values, IDs, payloads, timestamps,
signatures and lineage. Do not synthesize a founding agreement, approval, subject
recognition, root entitlement, signature or historical audit. Old records remain
readable. The Q08 cutover policy must decide how existing command consumers transition;
neither automatically blessing legacy roots nor silently revoking them all is part
of this migration. New mandate-dependent operations cannot treat unresolved basis as
verified. Activation requires a later explicit, authorized business transaction.

When approved, supply the actual numbered `.sqm`, updated current declarations,
registered runtime/data callback, fresh-compatible guard installer and target validator
under [ADR-031](adr/ADR-031-numbered-sqlite-migrations.md). Generated changes, data work,
guards, target/FK checks and marker commit together. No network, signing-key access,
filesystem side effects or cross-connection commits inside the callback. Fresh v2
creation must also contain no automatically active mandate. Reopening must not replay
either migration or governance activation. Preserve unsupported-version rejection.

## Future migration recovery procedure

1. Record exact source/target build commits, schema and binary hashes; supported source
   profile, operator, UTC time and reason. Stop commands, scheduler, peer listener and
   writers. Fence the node from authority execution and prevent duplicate active node
   identities. Capture a consistent verified backup using the [schema-1 procedure](node-backup-recovery.md).
2. Rehearse the exact approved build on an isolated copy. Check integrity, foreign keys,
   catalog, old-row digests, raw signed bytes/receipts, P10 archives/obligations and
   **absence of fabricated genesis evidence**. A test-only synthetic v2 is insufficient.
3. On opening failure, stop the failed process and close all handles. Preserve the
   failed database and SQLite sidecars together for diagnosis. Do not remove a hot
   journal/WAL, force the marker down, disable guards or re-run hand-written DDL.
4. For a rolled-back numbered transaction, reopen with the reviewed build and retry
   after resolving the cause. For committed target state, reopen without replaying
   steps. Legacy version-0 adoption is a separate compatibility path: completed
   baseline 1 may survive a later numbered-step failure, as ADR-031 specifies.
5. If restoring a backup, restore to a **new isolated path**, verify its digest and
   integrity, and use a binary known to support that schema. Keep the failed/post-backup
   copy and its evidence; recovery is not an in-place downgrade. Do not overwrite
   post-backup history or discard legitimate withdrawals that occurred after backup.
6. Before service rejoin, revalidate current peer/key policy and authenticated causal
   history, including withdrawals, mandate conflicts, electorate changes and clock
   freshness. A backup's successful checksum says nothing about authority freshness.
   Keep delegated operations fenced if current evidence cannot be established.
7. Verify checkpoints and exact history against peers, review conflicts, resume scoped
   P10 work and record a recovery audit. Do not merge raw database files, duplicate
   private identities across active nodes, or infer mandates from the recovery operator.

Current v1 has no Genesis freshness gate; step 6 is an operator deployment boundary
today and a future acceptance requirement, not an implemented revocation-resurrection
defense. No procedure can recover unbacked-up facts without another retained source.

## CI evidence and release gates

The existing [evidence collector](release-evidence.md) retains distributions, Gradle
JUnit reports, commit/tree/run identity and SHA256SUMS only after successful CI checks.
This increment also hashes the proposal, ADR, traceability/source manifest, questions,
acceptance/recovery plans and stopped-node runbook in `manifest.provenance`.
These hashes identify **review inputs**, not approved policy or passing Genesis tests.
Raw constitutional/axiom archives, private keys and operational databases are excluded.

For this review, require full `build` (including schema verifier and Detekt), collector
tests and the existing CI CLI/two-node smoke/rehearsal sequence. Confirm the final
workflow conclusion and artifact identity against the full tested commit. Local outputs
alone do not prove CI or freshness. The four recovery tests appear in ordinary JUnit
reports and in the focused migration task; do not count them twice.

Before a real schema-2 release, additionally retain:

- Explicit accepted ADR revision and resolved policy decisions; release commit/tag,
  supported source builds, target catalog and hashes of populated source/target fixtures.
- GEN-01–24 mapping to named executable tests, outcomes and remaining exclusions;
  include actual v1→v2 failure/crash/retry tests in DELETE/WAL and all four old profiles.
- Exact old-row/byte preservation and zero-auto-authority assertions; fresh/upgraded
  equivalence; ancestor and source checks across every consumer; partition/reorder and
  withdrawal/renewal races under controlled clocks.
- Restore rehearsal and operator rejoin evidence, with trust/freshness checks and
  bounded P10 continuity. Retain logs without private identity evidence or keys.
- Downloaded artifact digest, verified manifest/checksums and durable release storage.
  CI artifacts currently expire after 30 days; their presence is not a named release.

The release remains blocked on those future gates. Independent schema-1 node/P10
acceptance can pass now without changing that status.
