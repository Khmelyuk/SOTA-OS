package sotaos.api.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The JVM hook waits until host, HTTP client and SQLite use-blocks have all finished on the main thread. */
internal fun withSyncShutdown(run: (() -> Unit) -> Unit) {
    val stopping = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val shutdown = Thread({
        stopping.countDown()
        if (!closed.await(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            System.err.println("P09 shutdown did not complete within the deadline.")
        }
    }, "p09-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        run { stopping.await() }
    } finally {
        closed.countDown()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown)
        } catch (_: IllegalStateException) {
            // The JVM hook is already waiting for cleanup to complete.
        }
    }
}

private const val SHUTDOWN_SECONDS = 60L
