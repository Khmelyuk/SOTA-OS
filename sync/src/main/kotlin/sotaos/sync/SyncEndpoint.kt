package sotaos.sync

import sotaos.application.sync.SyncMessageCodec
import sotaos.domain.shared.SotaId
import java.nio.charset.StandardCharsets

/** The hosting server must derive authenticatedPeer from its authentication middleware. */
class SyncEndpoint(private val service: SyncService, private val codec: SyncMessageCodec) {
    fun exchange(authenticatedPeer: SotaId, body: String): String {
        require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_SYNC_MESSAGE_BYTES)
        val response = service.receive(authenticatedPeer, codec.decodeRequest(body))
        return codec.encodeResponse(response).also {
            require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_SYNC_MESSAGE_BYTES)
        }
    }
}
