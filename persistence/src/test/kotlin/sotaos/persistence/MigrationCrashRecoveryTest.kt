package sotaos.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class MigrationCrashRecoveryTest : FunSpec({
    for (journal in listOf("DELETE", "WAL")) {
        for (boundary in MigrationBoundary.entries) {
            test("$journal recovers original state after process halt at ${boundary.name}") {
                withProbeDatabase(journal) { path ->
                    val before = snapshot(path)
                    crashChild(path, boundary.name)
                    snapshot(path) shouldBe before
                    openProbe(path)
                    assertCompleted(path)
                }
            }
        }
        test("$journal retains committed migration after process halt and does not replay backfill") {
            withProbeDatabase(journal) { path ->
                crashChild(path, "COMMITTED")
                repeat(3) {
                    openProbe(path)
                    assertCompleted(path)
                }
            }
        }
    }
})

private fun crashChild(path: Path, boundary: String) {
    val log = path.resolveSibling("child.log")
    val process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m", "-cp",
        requireNotNull(System.getProperty("sota.migration.testClasspath")),
        "sotaos.persistence.MigrationCrashProbeKt", path.toString(), boundary
    ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
    try {
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Migration crash child timed out" }
        check(process.exitValue() == 73) { "Crash child failed: ${Files.readString(log)}" }
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Crash child could not be stopped" }
        }
    }
}
