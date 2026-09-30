package sotaos.api.cli

import sotaos.api.sync.*
import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.application.sync.SyncTransport
import sotaos.domain.shared.*
import sotaos.protocol.JsonSyncMessageCodec
import sotaos.security.LocalSyncIdentity
import sotaos.sync.HttpsSyncTransport
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Node host configuration is trusted local input. Provisioning remains a separate governed operation. */
internal fun runSyncCommand(arguments: Arguments) {
    require(arguments.subcommand in setOf("serve", "once", "run")) { "Use sync serve, sync once or sync run." }
    if (arguments.subcommand == "once") {
        runConfiguredSync(arguments) {}
    } else {
        withSyncShutdown { waitForStop -> runConfiguredSync(arguments, waitForStop) }
    }
}

private fun runConfiguredSync(arguments: Arguments, waitForStop: () -> Unit) {
    val database = requireNotNull(arguments.databasePath) { "Sync requires an explicit --db path." }
    require(Files.isRegularFile(database) && Files.size(database) > 0) {
        "Provision the database before starting sync."
    }
    val node = SotaId(arguments.required("node"))
    val actor = SubjectRef.Person(PersonId(arguments.required("actor")))
    val context = Context(arguments.required("governance-context"))
    withStore(database) { store, _ ->
        val runtime = P09Runtime(store, LocalSyncIdentity(node, setOf(actor)), context,
            Clock { Instant.now() }, IdGenerator { UUID.randomUUID().toString() })
        when (arguments.subcommand) {
            "serve" -> serveSync(arguments, runtime, waitForStop)
            "run" -> runSyncNode(arguments, runtime, waitForStop)
            else -> withOutboundTransport(arguments) { peer, transport ->
                val checkpoint = runtime.synchronize(peer, transport)
                println("P09 checkpoint: sent=${checkpoint.sent}, received=${checkpoint.received}")
            }
        }
    }
}

private fun serveSync(arguments: Arguments, runtime: P09Runtime, waitForStop: () -> Unit) {
    val tls = withTlsPassword { P09Tls.server(Path.of(arguments.required("keystore")), it) }
    P09HttpsServer(arguments.listenAddress(), tls, runtime).use { server ->
        println("P09 HTTPS listening on ${server.address}; path /p09. Stop with Ctrl+C.")
        waitForStop()
    }
}

private fun runSyncNode(arguments: Arguments, runtime: P09Runtime, waitForStop: () -> Unit) =
    withOutboundTransport(arguments) { peer, transport ->
    val tls = withTlsPassword { P09Tls.server(Path.of(arguments.required("keystore")), it) }
    val defaults = SyncLoopSettings()
    val settings = defaults.copy(
        interval = arguments.options["interval-seconds"]?.toLong()?.let(Duration::ofSeconds) ?: defaults.interval,
        maxBackoff = arguments.options["max-backoff-seconds"]?.toLong()?.let(Duration::ofSeconds)
            ?: defaults.maxBackoff)
    P09NodeHost(arguments.listenAddress(), tls, runtime, setOf(peer), transport, settings).use { host ->
        println("P09 node running on ${host.address}; background peer ${peer.value}. Stop with Ctrl+C.")
        waitForStop()
    }
}

private fun withOutboundTransport(arguments: Arguments, block: (SotaId, SyncTransport) -> Unit) {
    val tls = withTlsPassword { P09Tls.client(Path.of(arguments.required("truststore")), it) }
    val token = requireNotNull(System.getenv("SOTA_P09_PEER_TOKEN")) { "SOTA_P09_PEER_TOKEN is required." }
    require(token.isNotBlank() && token.none(Char::isWhitespace)) { "Invalid peer credential format." }
    val peer = SotaId(arguments.required("peer"))
    HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build().use { client ->
        val transport = HttpsSyncTransport(mapOf(peer to URI(arguments.required("endpoint"))),
            JsonSyncMessageCodec(), client) { "Bearer $token" }
        block(peer, transport)
    }
}

private fun Arguments.listenAddress(): InetSocketAddress {
    val bind = options["bind"] ?: "127.0.0.1"
    val port = required("port").toInt().also { require(it in 1..MAX_PORT) }
    return InetSocketAddress(bind, port)
}

private fun <T> withTlsPassword(block: (CharArray) -> T): T {
    val password = requireNotNull(System.getenv("SOTA_P09_TLS_PASSWORD")) {
        "SOTA_P09_TLS_PASSWORD is required."
    }.toCharArray()
    return try { block(password) } finally { password.fill('\u0000') }
}

private fun Arguments.required(name: String): String = requireNotNull(options[name]?.takeIf(String::isNotBlank)) {
    "--$name is required."
}

private const val MAX_PORT = 65535
