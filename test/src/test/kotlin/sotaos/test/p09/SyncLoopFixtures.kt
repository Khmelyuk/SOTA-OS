package sotaos.test.p09

import sotaos.api.sync.SyncLoopSettings
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.sync.HttpsSyncTransport
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal fun quickSyncSettings() = SyncLoopSettings(Duration.ofMillis(50), Duration.ofMillis(20),
    Duration.ofMillis(100), Duration.ofMillis(10))

internal class RestartableHttpsTransport(
    private val peer: SotaId,
    private val token: String,
    private val client: HttpClient
) : SyncTransport {
    val endpoint = AtomicReference<URI?>()
    override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
        check(peer == this.peer)
        val address = endpoint.get() ?: throw IOException("peer offline")
        return HttpsSyncTransport(mapOf(peer to address), messages, client) { "Bearer $token" }.exchange(peer, request)
    }
}

internal fun waitForSync(condition: () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    check(condition()) { "Background synchronization did not reach the expected state." }
}
