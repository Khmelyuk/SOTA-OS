package sotaos.api.cli

import sotaos.api.sync.*
import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
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
import java.util.concurrent.CountDownLatch

/** Node host configuration is trusted local input. Provisioning remains a separate governed operation. */
internal fun runSyncCommand(arguments: Arguments) {
    require(arguments.subcommand in setOf("serve", "once")) { "Use sync serve or sync once." }
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
        if (arguments.subcommand == "serve") serveSync(arguments, runtime) else synchronizeOnce(arguments, runtime)
    }
}

private fun serveSync(arguments: Arguments, runtime: P09Runtime) {
    val tls = withTlsPassword { P09Tls.server(Path.of(arguments.required("keystore")), it) }
    val bind = arguments.options["bind"] ?: "127.0.0.1"
    val port = arguments.required("port").toInt().also { require(it in 1..MAX_PORT) }
    P09HttpsServer(InetSocketAddress(bind, port), tls, runtime).use { server ->
        val stopped = CountDownLatch(1)
        val shutdown = Thread({
            try { server.close() } finally { stopped.countDown() }
        }, "p09-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            println("P09 HTTPS listening on ${server.address}; path /p09. Stop with Ctrl+C.")
            stopped.await()
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown)
            } catch (_: IllegalStateException) {
                // JVM shutdown is already running the hook.
            }
        }
    }
}

private fun synchronizeOnce(arguments: Arguments, runtime: P09Runtime) {
    val tls = withTlsPassword { P09Tls.client(Path.of(arguments.required("truststore")), it) }
    val token = requireNotNull(System.getenv("SOTA_P09_PEER_TOKEN")) { "SOTA_P09_PEER_TOKEN is required." }
    require(token.isNotBlank() && token.none(Char::isWhitespace)) { "Invalid peer credential format." }
    val peer = SotaId(arguments.required("peer"))
    HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build().use { client ->
        val transport = HttpsSyncTransport(mapOf(peer to URI(arguments.required("endpoint"))),
            JsonSyncMessageCodec(), client) { "Bearer $token" }
        val checkpoint = runtime.synchronize(peer, transport)
        println("P09 checkpoint: sent=${checkpoint.sent}, received=${checkpoint.received}")
    }
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
