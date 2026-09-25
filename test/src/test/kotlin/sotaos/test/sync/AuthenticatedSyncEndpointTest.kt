package sotaos.test.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.sync.SyncBatch
import sotaos.application.sync.SyncRequest
import sotaos.domain.shared.SotaId
import sotaos.sync.SyncEndpoint

class AuthenticatedSyncEndpointTest : FunSpec({
    test("endpoint authenticates the header before handling the exchange") {
        withNodes { a, b ->
            val request = SyncRequest("authenticated", b.id, 0, SyncBatch(0, 0, emptyList()))
            val endpoint = SyncEndpoint(a.service, messageCodec)
            val authenticate: (String) -> SotaId? = { header -> if (header == "Bearer peer-token") b.id else null }
            val response = messageCodec.decodeResponse(
                endpoint.exchange("Bearer peer-token", messageCodec.encodeRequest(request), authenticate)
            )
            response.sender shouldBe a.id
            shouldThrow<IllegalStateException> {
                endpoint.exchange("Bearer wrong", messageCodec.encodeRequest(request), authenticate)
            }
        }
    }
})
