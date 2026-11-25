package sotaos.test.rehearsal

import sotaos.persistence.SqlDelightSyncRepository
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.sync.HttpsSyncTransport
import sotaos.sync.SyncHttpException
import java.net.http.HttpClient
import java.nio.file.Path
import javax.net.ssl.SSLHandshakeException

/** Each receiver is a CLI child; only the other node's database is open in the parent. */
internal class RolloutNetwork(private val a: RehearsalNode, private val b: RehearsalNode,
    private val tokenForA: String, private val tokenForB: String) {
    private val ports = mutableMapOf<String, Int>()

    fun port(node: RehearsalNode): Int = ports[node.name] ?: 0

    fun round(aBundle: RolloutBundle, bBundle: RolloutBundle, checkBearer: Boolean = false) {
        send(a, b, aBundle, bBundle, tokenForB, checkBearer)
        send(b, a, bBundle, aBundle, tokenForA, checkBearer)
    }

    fun rejectRetired(aBundle: RolloutBundle, bBundle: RolloutBundle, old: RehearsalTls, password: Path) {
        val before = rolloutSnapshot(a, b) to rolloutSnapshot(b, a)
        reject(a, b, aBundle, old, password, tokenForB)
        reject(b, a, bBundle, old, password, tokenForA)
        check((rolloutSnapshot(a, b) to rolloutSnapshot(b, a)) == before) {
            "Retired certificates must not change journals or checkpoints."
        }
    }

    private fun send(node: RehearsalNode, peer: RehearsalNode, caller: RolloutBundle,
        receiver: RolloutBundle, token: String, checkBearer: Boolean) {
        rollbackClient(caller.trust, caller.password).use { client ->
            RehearsalServer(peer, receiver.tls, client, port(peer), receiver.password).use { server ->
                ports[peer.name] = server.endpoint.port
                if (checkBearer) {
                    val before = rolloutSnapshot(node, peer)
                    val failure = runCatching { exchange(node, peer, server, client, "wrong-rollout-token") }
                        .exceptionOrNull()
                    check(failure is SyncHttpException && failure.statusCode == UNAUTHORIZED)
                    check(rolloutSnapshot(node, peer) == before)
                }
                repeat(EXCHANGES) { exchange(node, peer, server, client, token) }
            }
        }
    }

    private fun reject(node: RehearsalNode, peer: RehearsalNode, caller: RolloutBundle,
        old: RehearsalTls, password: Path, token: String) {
        rollbackClient(old.trustStore(peer.name), password).use { readiness ->
            RehearsalServer(peer, old, readiness, port(peer), password).use { server ->
                rollbackClient(caller.trust, caller.password).use { client ->
                    val failure = runCatching { exchange(node, peer, server, client, token) }.exceptionOrNull()
                    check(generateSequence(failure) { it.cause }.any { it is SSLHandshakeException }) {
                        "A live retired server certificate must fail TLS, not an unrelated operation."
                    }
                }
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
}

internal fun rolloutSnapshot(node: RehearsalNode, peer: RehearsalNode) = node.access { store, _ ->
    val journal = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())
    journal.records() to journal.checkpoint(peer.id)
}

private const val EXCHANGES = 3
private const val UNAUTHORIZED = 401
