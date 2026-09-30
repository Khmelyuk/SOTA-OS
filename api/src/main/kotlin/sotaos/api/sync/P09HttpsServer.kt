package sotaos.api.sync

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/**
 * Bounded local pilot host. Give this server exclusive use of its runtime/store while running.
 * Public deployments also need connection, TLS-handshake and header limits at the network edge.
 */
class P09HttpsServer(
    address: InetSocketAddress,
    tls: SSLContext,
    runtime: P09Runtime,
    requestTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val server = HttpsServer.create(address, BACKLOG)
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(BACKLOG))
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    val address: InetSocketAddress get() = server.address

    init {
        var started = false
        try {
            require(!requestTimeout.isNegative && requestTimeout.toMillis() in 1..MAX_TIMEOUT_MILLIS)
            server.httpsConfigurator = object : HttpsConfigurator(tls) {
                override fun configure(parameters: HttpsParameters) {
                    val settings = tls.defaultSSLParameters
                    settings.protocols = arrayOf("TLSv1.3", "TLSv1.2")
                    parameters.setSSLParameters(settings)
                }
            }
            server.executor = worker
            server.createContext("/", P09HttpHandler(runtime, deadlines, requestTimeout))
            server.start()
            started = true
        } finally {
            if (!started) close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        worker.shutdownNow()
        deadlines.shutdownNow()
        check(worker.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) { "P09 worker did not stop." }
        check(deadlines.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)) { "P09 deadline worker did not stop." }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15L
        const val BACKLOG = 8
        const val CLOSE_SECONDS = 5L
        const val MAX_TIMEOUT_MILLIS = 60_000L
    }
}
