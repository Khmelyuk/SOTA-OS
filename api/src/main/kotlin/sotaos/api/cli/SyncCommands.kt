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
import java.time.Instant
import java.util.UUID

/** Node host configuration is trusted local input. Provisioning remains a separate governed operation. */
internal fun runSyncCommand(input: Arguments) {
    if (input.subcommand == "check") {
        require(input.databasePath == null && input.options.keys == setOf("config")) {
            "Use sync check --config PATH with a full sync run configuration."
        }
        checkSyncMaterial(resolveSyncConfiguration(input))
        return
    }
    val arguments = resolveSyncConfiguration(input)
    require(arguments.subcommand in setOf("serve", "once", "run")) { "Use sync serve, sync once or sync run." }
    arguments.options["status-interval-seconds"]?.let {
        require(arguments.subcommand == "run") { "Status output requires sync run." }
        require(it.toLongOrNull() in 1L..MAX_STATUS_INTERVAL_SECONDS) {
            "--status-interval-seconds must be an integer from 1 to 3600."
        }
    }
    arguments.options["metrics-format"]?.let {
        require(it == "json" && arguments.subcommand == "run" && "status-interval-seconds" in arguments.options) {
            "--metrics-format json requires sync run with --status-interval-seconds."
        }
    }
    arguments.options["reload-config"]?.let {
        require(it == "true" && arguments.configurationPath != null && arguments.subcommand == "run" &&
            "status-interval-seconds" in arguments.options) { "Reload requires a run config file and status interval." }
    }
    if (arguments.subcommand == "once") {
        runConfiguredSync(arguments) { true }
    } else {
        withSyncShutdown { waitForStop -> runConfiguredSync(arguments, waitForStop) }
    }
}

private fun runConfiguredSync(arguments: Arguments, waitForStop: (Long) -> Boolean) {
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

private fun serveSync(arguments: Arguments, runtime: P09Runtime, waitForStop: (Long) -> Boolean) {
    val tls = withTlsPassword { P09Tls.server(Path.of(arguments.required("keystore")), it) }
    P09HttpsServer(arguments.listenAddress(), tls, runtime).use { server ->
        println("P09 HTTPS listening on ${server.address}; path /p09. Stop with Ctrl+C.")
        waitForStop(Long.MAX_VALUE)
    }
}

private fun runSyncNode(arguments: Arguments, runtime: P09Runtime, waitForStop: (Long) -> Boolean) =
    withOutboundTransport(arguments) { peer, transport ->
    val tls = withTlsPassword { P09Tls.server(Path.of(arguments.required("keystore")), it) }
    val settings = P09ConfigurationReload.loopSettings(arguments.options)
    val reload = SyncReload(arguments)
    val statusMillis = arguments.options["status-interval-seconds"]?.toLong()?.times(MILLIS_PER_SECOND)
    val jsonMetrics = arguments.options["metrics-format"] == "json"
    val host = P09NodeHost(arguments.listenAddress(), tls, runtime, setOf(peer), transport, settings)
    host.use {
        val output = if (jsonMetrics) System.err else System.out
        output.println("P09 node running on ${host.address}; background peer ${peer.value}. Stop with Ctrl+C.")
        if (statusMillis == null) {
            waitForStop(Long.MAX_VALUE)
        } else {
            do {
                reload.poll(host)
                printNodeStatus(host, peer, jsonMetrics)
            } while (!waitForStop(statusMillis))
        }
    }
    if (statusMillis != null) printNodeStatus(host, peer, jsonMetrics)
}

private fun withOutboundTransport(arguments: Arguments, block: (SotaId, SyncTransport) -> Unit) {
    val tls = withTlsPassword { P09Tls.client(Path.of(arguments.required("truststore")), it) }
    val secrets = P09Secrets()
    secrets.peerToken() // Fail startup if the selected source is invalid; do not retain a file token.
    val peer = SotaId(arguments.required("peer"))
    HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build().use { client ->
        val transport = HttpsSyncTransport(mapOf(peer to URI(arguments.required("endpoint"))),
            JsonSyncMessageCodec(), client) { "Bearer ${secrets.peerToken()}" }
        block(peer, transport)
    }
}

private fun Arguments.listenAddress(): InetSocketAddress {
    val bind = options["bind"] ?: "127.0.0.1"
    val port = required("port").toInt().also { require(it in 1..MAX_PORT) }
    return InetSocketAddress(bind, port)
}

private fun <T> withTlsPassword(block: (CharArray) -> T): T {
    val password = P09Secrets().tlsPassword()
    return try { block(password) } finally { password.fill('\u0000') }
}

private fun Arguments.required(name: String): String = requireNotNull(options[name]?.takeIf(String::isNotBlank)) {
    "--$name is required."
}

private const val MAX_PORT = 65535

private const val MAX_STATUS_INTERVAL_SECONDS = 3600L
private const val MILLIS_PER_SECOND = 1000L

private fun resolveSyncConfiguration(input: Arguments): Arguments {
    val config = input.options["config"] ?: return input
    require(input.databasePath == null && input.options.keys == setOf("config")) {
        "--config cannot be combined with other sync options or --db."
    }
    val values = P09NodeConfiguration.load(Path.of(config),
        if (input.subcommand == "check") "run" else input.subcommand.orEmpty())
    return input.copy(databasePath = Path.of(values.getValue("db")), options = values - "db",
        configurationPath = Path.of(config).toAbsolutePath().normalize())
}

private fun printNodeStatus(host: P09NodeHost, peer: SotaId, json: Boolean) {
    val snapshot = host.snapshot()
    println(if (json) formatNodeMetrics(host.inboundMetrics(), snapshot.values)
        else formatSyncStatus(snapshot.getValue(peer)))
}
