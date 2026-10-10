# Reproduce the two-node Core Loop

This disposable rehearsal connects the production composition roots to two independent
SQLite stores and real HTTPS exchanges. A listener runs through `sync serve` in a
separate JVM. Both nodes run on one Linux machine; this is not a public deployment.

## Run

Install JDK 21 (including `keytool`) and Git. Set `JAVA_HOME` to that JDK and put
`$JAVA_HOME/bin` on `PATH`. The first Gradle run needs network access to dependencies.
From a fresh checkout:

```sh
git clone https://github.com/Khmelyuk/SOTA-OS.git
cd SOTA-OS
./scripts/two-node-core-loop.sh
```

The equivalent Gradle task is `./gradlew :test:twoNodeCoreLoop --console=plain`.
It compiles the fixture host and runs checks without requiring a prior build.
No existing database, credentials, or manual enrollment is needed. Each run creates
a new private temporary directory and binds available loopback ports. A failed
assertion or listener startup makes the command exit unsuccessfully.

## What is checked

1. Separate local authors and independent provisioning operators authenticate with
   the local passphrase provider. Explicit fixture Core authorities allow operators
   to enroll actor keys and peer policies through the governed runtime APIs.
2. Node A creates Mission, Authority, Decision, Action, Result, Experience and
   Knowledge offline. `SignedP05Runtime` writes the action's signed event atomically.
3. Node B serves strict P09 over HTTPS with an ephemeral certificate trusted explicitly
   by A. A wrong bearer is denied. A valid exchange commits on B, then the caller
   deliberately discards the response. A's checkpoint remains zero while B retains
   the fact. After closing both stores and restarting B's listener, retry converges
   without duplicate events.
4. With the listener stopped, A performs signed P10 exit: export, delegation revocation,
   relation closure, obligation retention and membership termination. The export's
   SHA-256 and mode `0600` are checked, and the former action authority is denied.
5. A now serves HTTPS in a separate JVM; B exchanges in the reverse direction.
   A final outbound exchange from A acknowledges its remaining records. Both stores
   reopen with seven identical signed records and cursors `(7, 7)`:
   one action fact and six P10 stages. Person/Identity and the other member survive.
   B receives facts but does not execute actions or apply remote membership changes.

Expected final output includes:

```text
PASS two-node Core Loop -> strict HTTPS P09 -> signed P10 exit
```

The printed `Rehearsal artifacts:` directory contains `a.db`, `b.db`, `exit.json`,
listener logs, and `report.txt`. The report is written only after all assertions
pass. The directory is private (`0700`); the shell uses `umask 077`. Temporary TLS
key stores are removed on normal completion or handled failure. Signing keys,
passphrases and bearer credentials are ephemeral and not printed. Artifacts are
for inspection; a subsequent run creates a fresh scenario rather than resuming them.

## Scope and assumptions

The initial Core, memberships, relation and obligation inventory are explicit,
shared test fixtures. Initial Core-issued operator authorities are trust anchors,
not evidence that general production enrollment has been solved. Subsequent peer
and key enrollment uses normal authorization and audit. Each node owns its store;
the parent closes it before the listener process opens it.

Only the action and P10 stages are signed journal producers here. Result, Experience,
and Knowledge remain local persisted records, not replicated business commands.
The scenario does not upgrade unsigned `demo` history. It uses hostname verification
and explicit certificate trust, without a trust-all TLS client.

CI runs this command after the full build and existing CLI smokes. For provisioning
contracts and deployment on separately operated nodes, see the
[P09 HTTPS pilot runbook](p09-https-pilot.md), [signed actions](p05-signed-actions.md),
and [AC mapping](ac-p09-mapping.md).
