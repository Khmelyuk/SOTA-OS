# Stopped-node backup and P10 recovery — schema 1

Updated: 2026-11-28. This procedure applies to restoration of the **same logical node**
from a consistent recovery point. It does not authorize cloning a node's identity or
moving local verification receipts into a different trust domain. Production remains
schema 1; [Genesis recovery](core-genesis-mandate-recovery.md) is a separate proposal.

## Capture and verify

1. Identify the node, exact application commit/distribution hash, database path and
   schema version. Record the time and outstanding P10 requests. Stop its commands,
   background scheduler, peer listener and all other database writers; confirm their
   shutdown using the deployment's process supervisor. Do not run two active instances
   with the same identity during recovery.
2. Use a private backup directory on suitable storage. While the node is stopped,
   open SQLite and create a **new consistent database snapshot**, for example with
   `VACUUM INTO '/absolute/private/backup/node.db';`. Substitute a new destination;
   do not overwrite the only recovery point. This is a SQLite operation, not a raw
   copy of the main file while ignoring a WAL or hot journal.
3. On the backup, require `PRAGMA integrity_check` = `ok`, empty
   `PRAGMA foreign_key_check`, and `PRAGMA user_version` = `1`. Record SHA-256 and size
   after closing verification connections. A different schema requires its own reviewed
   build/procedure. Preserve the original database and associated sidecars.
4. Separately retain the deployment's matching configuration and protected key material
   under its existing access controls. A database-only backup does not restore TLS keys,
   signing capability, environment secrets or external data. Never put those materials
   in Git, test fixtures or the public CI evidence bundle.

## Restore and resume

1. Keep the failed node stopped/fenced. Preserve its database/sidecars and any newer
   evidence for diagnosis. Copy the verified snapshot to a new isolated path; compare
   its SHA-256 to the retained backup before opening. Abort on mismatch; do not use
   a corrupt copy or replace the original to “try it”.
2. Open the replacement with the exact supported schema-1 build. Recheck integrity/FKs,
   marker, catalog/guards and relevant row counts/digests. Check exact signed records,
   local verification receipts and key/peer revisions; do not re-sign old events or
   infer current key validity from a historical signature.
3. For P10, compare completed archive bytes/hash and approvals, immutable transition
   history, retained/fulfilled obligations and unrelated Core membership. Continue an
   unfinished exit through its existing authenticated service, respecting export digest
   and termination ordering. Do not manually reset exit stages or re-open memberships.
4. Before enabling normal exchange and commands, validate current TLS/key/peer policy
   and reconcile retained post-backup signed facts/checkpoints with authenticated peers.
   A valid snapshot may be stale: known revocations, approvals withdrawn since backup
   and unresolved conflicts must not be discarded. If the required current evidence
   cannot be recovered, keep affected operations fenced and record the gap. Current
   schema 1 does not automate this recovery freshness gate.
5. Resume only one instance of the logical node. Record the recovery point, exact
   build, checks, reconciled boundaries and unresolved gaps. Preserve the original
   backup unchanged; changes belong to the replacement. Do not combine database files
   or force `user_version` down to make an old binary accept a new schema.

## Executable rehearsal

```bash
./gradlew :test:test --tests sotaos.test.migration.StoppedNodeRecoveryTest :test:detekt
./gradlew :test:migrationAcceptance
```

`StoppedNodeRecoveryTest` runs four cases: frozen runtime-signed and runtime-P10
profiles, each using DELETE and WAL journal modes. After current baseline opening,
it takes a stopped-node `VACUUM INTO` snapshot, restores to a different temporary
file, compares checksums, every application table's rows and full schema catalog,
and verifies integrity/FKs/version. Repeated store opening preserves all rows.
Every restored signed journal record retains an exact receipt and valid Ed25519
signature. The P09 case admits exact recorded old-key history while rejecting unknown
old-key and revoked-approval history. The P10 case completes its pending exit
idempotently, keeps its earlier archive and retained obligations, and leaves an
unrelated Core unaffected. The backup and source rows remain unchanged.

All data and keys used by the fixtures are synthetic. This rehearsal adds restore
coverage to the existing historical migration tests; it does not test live-writer
backup, uncheckpointed active WAL capture, arbitrary corruption, power loss or
discovery of a remote revocation absent from the snapshot. Existing TLS rollback/
rollout and two-node rehearsals cover different operational boundaries. Multiparty
consent and additional P10 settlement policies remain planned, independently of ADR-032.
