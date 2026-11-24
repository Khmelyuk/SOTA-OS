package sotaos.test.rehearsal

import sotaos.application.sync.PeerCheckpoint
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.sync.HttpsSyncTransport
import sotaos.sync.SyncHttpException
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.net.ssl.SSLHandshakeException

fun main() {
    val directory = rollbackDirectory()
    println("TLS rollback artifacts: $directory")
    runTlsRollback(directory)
    println(Files.readString(directory.resolve("report.txt")))
}

internal fun rollbackDirectory(): Path = Files.createTempDirectory("sota-tls-rollback-",
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

/** All listeners are real CLI children; database handles close before each child starts. */
internal fun runTlsRollback(directory: Path) {
    val a = RehearsalNode("a", directory)
    val b = RehearsalNode("b", directory)
    val core = bootstrapCore(a, b)
    val tokenForA = a.enroll(b, core.id)
    val tokenForB = b.enroll(a, core.id)
    executeCoreLoop(a, core)
    RehearsalTls(directory).use { current -> RehearsalTls(directory).use { candidate ->
        check(current.password != candidate.password)
        RollbackCli(directory, current, candidate, tokenForA).use { cli ->
            val port = baseline(a, b, current, cli, tokenForB)
            executeCoreLoop(b, core)
            val before = snapshot(a, b) to snapshot(b, a)
            val previous = cli.configuration("current", b, a, current, port)
            val staged = cli.configuration("candidate", b, a, candidate, port)
            val files = listOf(previous, staged, cli.currentPassword, cli.candidatePassword) +
                listOf(current, candidate).flatMap { tls ->
                    listOf("a", "b").flatMap { listOf(tls.keyStore(it), tls.trustStore(it)) }
                }
            val originalFiles = fileDigests(files)
            val databases = fileDigests(listOf(a.path, b.path))
            cli.check(staged, cli.candidatePassword, "candidate-check")
            cli.compare(previous, staged)
            check(fileDigests(listOf(a.path, b.path)) == databases)
            rejectCandidate(a, b, current, candidate, cli, port, tokenForA to tokenForB)
            check((snapshot(a, b) to snapshot(b, a)) == before)
            cli.check(previous, cli.candidatePassword, "wrong-recovery-password", succeeds = false)
            cli.check(previous, cli.currentPassword, "recovery-check")
            recover(a, b, current, cli, port, tokenForA, tokenForB)
            verifyRollback(a, b)
            check(fileDigests(files) == originalFiles)
        }
    } }
    Files.list(directory).use { paths ->
        check(paths.noneMatch { it.fileName.toString().startsWith("tls-") ||
            it.fileName.toString().startsWith("secrets-") })
    }
    Files.writeString(directory.resolve("report.txt"), ROLLBACK_REPORT + "\n")
}

private fun baseline(a: RehearsalNode, b: RehearsalNode, tls: RehearsalTls,
    cli: RollbackCli, token: String): Int = rollbackClient(tls.trustStore("b"), cli.currentPassword).use { client ->
    RehearsalServer(b, tls, client, passwordFile = cli.currentPassword).use { server ->
        repeat(EXCHANGES) { exchange(a, b, server, client, token) }
        server.endpoint.port
    }.also {
        checkJournal(a, b, INITIAL_RECORDS)
        checkJournal(b, a, INITIAL_RECORDS, sent = 0)
    }
}

private fun rejectCandidate(a: RehearsalNode, b: RehearsalNode, current: RehearsalTls,
    candidate: RehearsalTls, cli: RollbackCli, port: Int, tokens: Pair<String, String>) {
    rollbackClient(candidate.trustStore("b"), cli.candidatePassword).use { readiness ->
        RehearsalServer(b, candidate, readiness, port, cli.candidatePassword).use { server ->
            rollbackClient(current.trustStore("b"), cli.currentPassword).use { stale ->
                rejectExchange(a, b, server, stale, tokens.second)
            }
        }
    }
    rollbackClient(current.trustStore("a"), cli.currentPassword).use { readiness ->
        RehearsalServer(a, current, readiness, passwordFile = cli.currentPassword).use { server ->
            rollbackClient(candidate.trustStore("a"), cli.candidatePassword).use { incompatible ->
                rejectExchange(b, a, server, incompatible, tokens.first)
            }
        }
    }
}

private fun rejectExchange(node: RehearsalNode, peer: RehearsalNode, server: RehearsalServer,
    client: HttpClient, token: String) {
    val failure = runCatching { exchange(node, peer, server, client, token) }.exceptionOrNull()
    check(generateSequence(failure) { it.cause }.any { it is SSLHandshakeException }) {
        "Candidate must fail TLS trust, not authentication or an unrelated operation."
    }
}

private fun recover(a: RehearsalNode, b: RehearsalNode, current: RehearsalTls,
    cli: RollbackCli, port: Int, tokenForA: String, tokenForB: String) {
    rollbackClient(current.trustStore("b"), cli.currentPassword).use { client ->
        RehearsalServer(b, current, client, port, cli.currentPassword).use { server ->
            val before = snapshot(a, b)
            val failure = runCatching { exchange(a, b, server, client, "wrong-rollback-token") }.exceptionOrNull()
            check(failure is SyncHttpException && failure.statusCode == UNAUTHORIZED)
            check(snapshot(a, b) == before)
            repeat(EXCHANGES) { exchange(a, b, server, client, tokenForB) }
        }
    }
    rollbackClient(current.trustStore("a"), cli.currentPassword).use { client ->
        RehearsalServer(a, current, client, passwordFile = cli.currentPassword).use { server ->
            repeat(EXCHANGES) { exchange(b, a, server, client, tokenForA) }
        }
    }
}

private fun exchange(node: RehearsalNode, peer: RehearsalNode, server: RehearsalServer,
    client: HttpClient, token: String) = node.access { store, _ ->
    val transport = HttpsSyncTransport(mapOf(peer.id to server.endpoint), JsonSyncMessageCodec(), client) {
        "Bearer $token"
    }
    node.runtime(store).synchronize(peer.id, transport)
}

private data class RollbackSnapshot(val records: List<SyncRecord>, val checkpoint: PeerCheckpoint)

private fun snapshot(node: RehearsalNode, peer: RehearsalNode) = node.access { store, _ ->
    val journal = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())
    RollbackSnapshot(journal.records(), journal.checkpoint(peer.id))
}

private fun verifyRollback(a: RehearsalNode, b: RehearsalNode) {
    val records = checkJournal(a, b, FINAL_RECORDS)
    check(records == checkJournal(b, a, FINAL_RECORDS))
    records.forEach { record ->
        val origin = if (record.origin == a.id) a else b
        check(record.origin == origin.id && record.event.actor == origin.actor)
        check(origin.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)))
    }
}

private const val INITIAL_RECORDS = 4
private const val FINAL_RECORDS = 8
private const val EXCHANGES = 3
private const val UNAUTHORIZED = 401
private val ROLLBACK_REPORT = """
    PASS P09 TLS replacement failure and rollback
    Candidate preflight and comparison passed with an independent password.
    Candidate CLI listener started on the original port; stale peer trust rejected its TLS certificate.
    Candidate outbound trust also rejected the unchanged peer certificate.
    Both journals and checkpoints remained unchanged during the failed exchange.
    Wrong recovery password failed preflight; previous material passed before restart.
    Previous certificate, trust and password restored; wrong bearer still rejected.
    Both journals: 8 identical signed records; both checkpoints: sent=8 received=8; no duplicates.
    Configuration, TLS material and password files remained unchanged; ephemeral secrets removed.
    Fixture-only localhost rehearsal; no issuer, automatic rollback or production deployment.
""".trimIndent()
