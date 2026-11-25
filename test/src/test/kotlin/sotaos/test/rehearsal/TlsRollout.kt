package sotaos.test.rehearsal

import sotaos.domain.collective.Core
import sotaos.domain.sync.SyncRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

fun main() {
    val directory = rolloutDirectory()
    println("TLS rollout artifacts: $directory")
    runTlsRollout(directory)
    println(Files.readString(directory.resolve("report.txt")))
}

internal fun rolloutDirectory(): Path = Files.createTempDirectory("sota-tls-rollout-",
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

internal fun runTlsRollout(directory: Path) {
    val a = RehearsalNode("a", directory)
    val b = RehearsalNode("b", directory)
    val core = bootstrapCore(a, b)
    val tokenForA = a.enroll(b, core.id)
    val tokenForB = b.enroll(a, core.id)
    val network = RolloutNetwork(a, b, tokenForA, tokenForB)
    RehearsalTls(directory).use { current -> RehearsalTls(directory).use { candidate ->
        check(current.password != candidate.password)
        withRolloutMaterial(directory, current, candidate, tokenForA) { cli, material ->
            performRollout(a, b, core, network, current, cli, material)
        }
    } }
    Files.list(directory).use { paths -> check(paths.noneMatch { Files.isDirectory(it) }) }
    Files.writeString(directory.resolve("report.txt"), ROLLOUT_REPORT + "\n")
}

private fun withRolloutMaterial(directory: Path, current: RehearsalTls, candidate: RehearsalTls,
    token: String, block: (RollbackCli, RolloutMaterial) -> Unit) {
    RollbackCli(directory, current, candidate, token).use { cli ->
        RolloutMaterial(directory, current, candidate, cli).use { material -> block(cli, material) }
    }
}

private fun performRollout(a: RehearsalNode, b: RehearsalNode, core: Core, network: RolloutNetwork,
    current: RehearsalTls, cli: RollbackCli, material: RolloutMaterial) {
    val original = fileDigests(material.files())
    var aBundle = material.bundle(a, newKey = false, overlap = false)
    var bBundle = material.bundle(b, newKey = false, overlap = false)
    executeCoreLoop(a, core)
    network.round(aBundle, bBundle)
    var history = verifyStage(a, b, RECORDS_PER_LOOP, emptyList())
    for (stage in rolloutStages(a, b, material)) {
        executeCoreLoop(if (stage.produceOnA) a else b, core)
        preflight(cli, stage.name + "-a", a, b, network.port(a), aBundle, stage.aBundle)
        preflight(cli, stage.name + "-b", b, a, network.port(b), bBundle, stage.bBundle)
        aBundle = stage.aBundle
        bBundle = stage.bBundle
        network.round(aBundle, bBundle)
        history = verifyStage(a, b, history.size + RECORDS_PER_LOOP, history)
    }
    executeCoreLoop(b, core)
    network.rejectRetired(aBundle, bBundle, current, cli.currentPassword)
    network.round(aBundle, bBundle, checkBearer = true)
    verifyStage(a, b, FINAL_RECORDS, history)
    check(fileDigests(material.files()) == original)
}

private data class RolloutStage(val name: String, val aBundle: RolloutBundle,
    val bBundle: RolloutBundle, val produceOnA: Boolean)

private fun rolloutStages(a: RehearsalNode, b: RehearsalNode, material: RolloutMaterial) = listOf(
    RolloutStage("overlap", material.bundle(a, false, true), material.bundle(b, false, true), false),
    RolloutStage("switch-a", material.bundle(a, true, true), material.bundle(b, false, true), true),
    RolloutStage("switch-b", material.bundle(a, true, true), material.bundle(b, true, true), false),
    RolloutStage("retire", material.bundle(a, true, false), material.bundle(b, true, false), true)
)

private fun preflight(cli: RollbackCli, name: String, node: RehearsalNode, peer: RehearsalNode,
    port: Int, previous: RolloutBundle, candidate: RolloutBundle) {
    val old = cli.configuration("$name-current", node, peer, previous.tls, port, previous.trust)
    val next = cli.configuration("$name-candidate", node, peer, candidate.tls, port, candidate.trust)
    val before = fileDigests(listOf(node.path, peer.path, old, next))
    cli.check(next, candidate.password, "$name-check")
    cli.compare(old, next, "$name-compare", previous.password, candidate.password)
    check(fileDigests(before.keys.toList()) == before)
}

private fun verifyStage(a: RehearsalNode, b: RehearsalNode, count: Int, history: List<SyncRecord>): List<SyncRecord> {
    val records = checkJournal(a, b, count)
    check(records == checkJournal(b, a, count))
    check(records.take(history.size) == history) { "Earlier signed history must remain exact across TLS stages." }
    records.forEach { record ->
        val origin = if (record.origin == a.id) a else b
        check(record.origin == origin.id && record.event.actor == origin.actor)
        check(origin.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)))
    }
    return records
}

private const val RECORDS_PER_LOOP = 4
private const val FINAL_RECORDS = 24
private val ROLLOUT_REPORT = """
    PASS P09 overlapping-trust TLS rollout and retirement
    Both directions exchanged new signed records at every stage on fixed listener ports.
    Stages: old=4, overlap=8, A-new/B-old=12, both-new=16, new-only-trust=20 records.
    Each staged configuration passed real CLI check and comparison without changing database bytes.
    Live old certificates were rejected by new-only trust in both directions; journals/checkpoints unchanged.
    New material resumed exchange; wrong bearer still rejected in both directions.
    Both journals: 24 identical signed records; both checkpoints: sent=24 received=24; no duplicates.
    Earlier signed history and all staged TLS/password files remained unchanged; ephemeral material removed.
    Fixture-only localhost rehearsal with CLI listeners; not automatic production rollout or CA issuance.
""".trimIndent()
