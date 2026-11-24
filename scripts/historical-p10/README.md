# Capturing historical P10 state

`CaptureP10.kt` runs only in an isolated checkout of
`3c1a4aa1d20cf77179e3c37580ddd682b329d018`. It uses that revision's ExitFixture,
P10/SignedP10Runtime, repositories, codecs and signer to create synthetic history.
It never reads a working node. Current acceptance tests only load frozen JSON.

From the current repository:

```bash
p10_checkout=$(mktemp -d /tmp/sota-p10-authoring-XXXXXX)
p10_output="$p10_checkout/captured"
git archive 3c1a4aa1d20cf77179e3c37580ddd682b329d018 | tar -x -C "$p10_checkout"
cp scripts/historical-p10/CaptureP10.kt \
  "$p10_checkout/test/src/test/kotlin/sotaos/test/acceptance/"
cat >> "$p10_checkout/test/build.gradle.kts" <<'GRADLE'

tasks.register<JavaExec>("captureHistoricalP10") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sotaos.test.acceptance.CaptureP10Kt")
    args(providers.environmentVariable("SOTA_P10_CAPTURE_OUTPUT").get())
}
GRADLE
(cd "$p10_checkout" && SOTA_P10_CAPTURE_OUTPUT="$p10_output" \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :test:captureHistoricalP10 --console=plain)
node scripts/historical-runtime/freeze-capture.mjs "$p10_output" new-reviewed-p10 p10
```

Only the authoring main and JavaExec task are added to the archived checkout.
Production sources are unchanged. The task compiles old test helpers but does not
run the historical test suite. Gradle dependency access is needed only for authoring.

The captured state contains:

- A completed signed exit from `chosen`, with six causal signed events, journal
  records and local verification receipts, revoked authority and terminated membership.
- An approved archive with exact Event/Knowledge/Evidence snapshots, typed Trust and
  Agreement terms, a retained equipment obligation and separately fulfilled work.
  Independent export/fulfillment audit and the public signing key are retained.
- A second, unsigned exit from `other` at REQUESTED, with live commitments to resume.
- An `unaffected` Core with active membership, authority, relation and an open obligation.

The result is 38 tables, two indexes, 50 triggers and 68 rows. Internal SQLite
objects, including `sqlite_sequence`, are excluded; explicit historical IDs remain
in the rows. Captured rows are a final database snapshot, not an insertion history:
restore them in one transaction with deferred foreign-key checks, install captured
triggers after the rows, then commit. All foreign keys and guards must be active
before current store initialization. This avoids treating restoration as new
commitments during an already pending exit.

The manifest binds the full Git revision, old persistence/ExitFixture source hashes
and the exact capture program. `vectors.json` pins both process IDs and the six
canonical signed records. Private signing keys exist only in memory and are never
exported. Re-running authoring generates new UUIDs/keys/signatures; review and pin a
new profile rather than overwriting an existing one. Ordinary tests require neither
Git, the old checkout nor a private key.
