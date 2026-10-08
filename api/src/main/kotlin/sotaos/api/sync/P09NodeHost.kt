package sotaos.api.sync

import sotaos.application.sync.SyncTransport
import sotaos.domain.shared.SotaId
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

/** Owns listener and retries; caller retains the store and transport until close returns. */
class P09NodeHost(
    address: InetSocketAddress,
    tls: SSLContext,
    runtime: P09Runtime,
    peers: Set<SotaId>,
    transport: SyncTransport,
    settings: SyncLoopSettings = SyncLoopSettings()
) : AutoCloseable {
    private val server = P09HttpsServer(address, tls, runtime)
    private val loop: P09SyncLoop
    val address: InetSocketAddress get() = server.address

    init {
        var started = false
        try {
            loop = P09SyncLoop(runtime, peers, transport, settings)
            started = true
        } finally {
            if (!started) server.close()
        }
    }

    fun updateSettings(settings: SyncLoopSettings) = loop.updateSettings(settings)

    fun inboundMetrics(): InboundMetrics = server.inboundMetrics()

    fun snapshot(): Map<SotaId, PeerSyncStatus> = loop.snapshot()

    override fun close() {
        try { loop.close() } finally { server.close() }
    }
}
