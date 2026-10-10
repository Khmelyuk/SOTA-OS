package sotaos.test.rehearsal

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Runs the real production CLI in a different JVM; no open parent store shares its database. */
internal class RehearsalServer(node: RehearsalNode, tls: RehearsalTls, client: HttpClient) : AutoCloseable {
    private val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val endpoint: URI = URI("https://localhost:$port/p09")
    private val log = node.path.resolveSibling("${node.name}-server.log")
    private val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), "sotaos.api.cli.MainKt", "sync", "serve",
        "--db", node.path.toString(), "--node", node.name, "--actor", node.person.id.value,
        "--governance-context", governance.domain, "--keystore", tls.keyStore(node.name).toString(),
        "--bind", "127.0.0.1", "--port", port.toString())
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .apply {
            environment().keys.removeIf { it.startsWith("SOTA_P09_") }
            environment()["SOTA_P09_TLS_PASSWORD"] = tls.password
        }.start()

    init {
        runCatching { awaitReady(client) }.onFailure { close() }.getOrThrow()
    }

    private fun awaitReady(client: HttpClient) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_SECONDS)
        var ready = false
        while (!ready && process.isAlive && System.nanoTime() < deadline) {
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build()
            ready = try {
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == METHOD_NOT_ALLOWED
            } catch (_: IOException) {
                false
            }
            if (!ready) Thread.sleep(POLL_MILLIS)
        }
        check(ready) { "Rehearsal listener did not start; inspect $log" }
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Rehearsal listener required forced termination; inspect $log")
        }
    }
}

private const val STARTUP_SECONDS = 30L
private const val STOP_SECONDS = 20L
private const val POLL_MILLIS = 100L
private const val METHOD_NOT_ALLOWED = 405
