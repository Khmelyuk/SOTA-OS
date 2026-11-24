# Capturing a historical runtime fixture

`CaptureRuntime.kt` is an authoring program for revision
`3c1a4aa1d20cf77179e3c37580ddd682b329d018`. It uses that revision's test helpers,
SQLDelight schema, bootstrap, codecs, Ed25519 signer and P09 services. It does not
use a working node or its data. Tests in the current checkout only read frozen JSON.

From the current repository, with JDK 21 available:

```bash
capture_checkout=$(mktemp -d /tmp/sota-historical-authoring-XXXXXX)
capture_output="$capture_checkout/captured"
git archive 3c1a4aa1d20cf77179e3c37580ddd682b329d018 | tar -x -C "$capture_checkout"
cp scripts/historical-runtime/CaptureRuntime.kt \
  "$capture_checkout/test/src/test/kotlin/sotaos/test/p09/"
cat >> "$capture_checkout/test/build.gradle.kts" <<'GRADLE'

tasks.register<JavaExec>("captureHistoricalRuntime") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("sotaos.test.p09.CaptureRuntimeKt")
    args(providers.environmentVariable("SOTA_CAPTURE_OUTPUT").get())
}
GRADLE
(cd "$capture_checkout" && SOTA_CAPTURE_OUTPUT="$capture_output" \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :test:captureHistoricalRuntime --console=plain)
node scripts/historical-runtime/freeze-capture.mjs "$capture_output" new-reviewed-profile
```

The archived production sources are unchanged. Only this authoring program and a
JavaExec task are added to the disposable checkout. The task compiles the old test
helpers but does not run the historical test suite. Gradle needs its dependencies;
ordinary acceptance tests need neither Git, Node nor this historical build.

The capture creates one locally verified record, retires its signing key, keeps a
separate active key, rotates a live peer, revokes another peer and records two
independently approved old-key records, one subsequently revoked. It also emits a
valid but never received/approved old-key record as a denial vector. Old runtime
`sqlite_master` SQL and raw row values are captured after those operations. Internal
SQLite objects, including `sqlite_sequence`, are excluded; explicit audit/journal
IDs are preserved in the raw rows.

Private signing keys exist only in memory and are never exported. Generated bearer
tokens are discarded; their stored hashes are replaced with the documented public
fixture vectors before capture. This substitution is test data preparation, not a
production credential rotation. Public vectors are `public-historical-peer-token`
and `public-retired-peer-token`.

A new run generates new public keys/signatures, so its resource hashes will differ.
Review its semantics and source manifest, pin all three hashes in the test loader,
and add appropriate acceptance assertions. Never regenerate an existing profile in
place to make a failing migration pass. The freezer refuses an existing directory.
The checked-in profile's authoring-source hash binds the exact capture program;
its source inventory additionally fingerprints old persistence and helper files.
