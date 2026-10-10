package sotaos.test.rehearsal

import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.sync.HttpsSyncTransport
import sotaos.sync.SyncHttpException
import java.io.IOException
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Disposable fixture host using production runtimes, not a production provisioning command. */
fun main() {
    val directory = Files.createTempDirectory("sota-two-node-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    println("Rehearsal artifacts: $directory")
    val a = RehearsalNode("a", directory)
    val b = RehearsalNode("b", directory)
    val core = bootstrapCore(a, b)
    val tokenForB = a.enroll(b, core.id)
    val tokenForA = b.enroll(a, core.id)
    val decision = executeCoreLoop(a, core)
    println("PASS offline Core Loop: signed action, Result, Experience, Knowledge")
    RehearsalTls(directory).use { tls ->
        tls.client("b").use { client -> recoverLostReply(a, b, tls, client, tokenForA) }
        val receipt = leaveCore(a, core, decision, directory.resolve("exit.json"))
        println("PASS offline P10: private export, revoked authority, closed relations, terminated membership")
        tls.client("a").use { client ->
            RehearsalServer(a, tls, client).use { server ->
                val transport = transport(a, server, client, tokenForB)
                repeat(EXCHANGES) { b.access { store, _ -> b.runtime(store).synchronize(a.id, transport) } }
            }
        }
        tls.client("b").use { client ->
            RehearsalServer(b, tls, client).use { server ->
                a.access { store, _ -> a.runtime(store).synchronize(b.id, transport(b, server, client, tokenForA)) }
            }
        }
        verifyFinalState(a, b, core, receipt)
    }
    val report = """
        PASS two-node Core Loop -> strict HTTPS P09 -> signed P10 exit
        Wrong bearer rejected; lost acknowledgement recovered after restart.
        Both journals: 7 identical signed records; both checkpoints: sent=7 received=7.
        Exit export SHA-256 verified; permissions 0600; authority revoked; membership terminated.
        Remote facts did not execute actions or mutate the receiving node's Core membership.
        Fixture-only bootstrap; ephemeral credentials and TLS keys are not retained for reuse.
    """.trimIndent() + "\n"
    Files.writeString(directory.resolve("report.txt"), report)
    println(report)
}

private fun recoverLostReply(
    a: RehearsalNode, b: RehearsalNode, tls: RehearsalTls, client: HttpClient, token: String
) {
    RehearsalServer(b, tls, client).use { server ->
        a.access { store, _ ->
            val runtime = a.runtime(store)
            val rejected = runCatching {
                runtime.synchronize(b.id, transport(b, server, client, "invalid-rehearsal-token"))
            }.exceptionOrNull()
            check(rejected is SyncHttpException && rejected.statusCode == UNAUTHORIZED)
            val https = transport(b, server, client, token)
            val lost = IOException("Simulated lost acknowledgement after receiver commit")
            val dropping = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    https.exchange(peer, request)
                    throw lost
                }
            }
            check(runCatching { runtime.synchronize(b.id, dropping) }.exceptionOrNull() === lost)
        }
    }
    a.access { store, _ ->
        check(SqlDelightSyncRepository(store.database, JsonSyncRecordCodec()).checkpoint(b.id) == PeerCheckpoint())
    }
    b.access { store, _ ->
        check(SqlDelightSyncRepository(store.database, JsonSyncRecordCodec()).records().size == 1)
    }
    RehearsalServer(b, tls, client).use { server ->
        repeat(EXCHANGES) {
            a.access { store, _ -> a.runtime(store).synchronize(b.id, transport(b, server, client, token)) }
        }
    }
    checkJournal(a, b, 1)
    checkJournal(b, a, 1, sent = 0)
    println("PASS strict P09: wrong bearer denied, lost reply recovered after SQLite and listener restart")
}

private fun transport(node: RehearsalNode, server: RehearsalServer, client: HttpClient, token: String) =
    HttpsSyncTransport(mapOf(node.id to server.endpoint), JsonSyncMessageCodec(), client) { "Bearer $token" }

private const val EXCHANGES = 3
private const val UNAUTHORIZED = 401
