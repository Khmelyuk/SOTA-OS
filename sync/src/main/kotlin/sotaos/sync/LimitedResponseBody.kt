package sotaos.sync

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Cancel the HTTP body as soon as it exceeds the bound, before accumulating it in memory. */
internal class LimitedResponseBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private var subscription: Flow.Subscription? = null
    private var remaining = limit.toLong()

    override fun getBody(): CompletionStage<ByteArray> = delegate.body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        delegate.onSubscribe(subscription)
    }

    override fun onNext(item: List<ByteBuffer>) {
        remaining -= item.sumOf { it.remaining().toLong() }
        if (remaining < 0) {
            subscription?.cancel()
            delegate.onError(IllegalArgumentException("Sync response exceeds size limit."))
        } else {
            delegate.onNext(item)
        }
    }

    override fun onError(throwable: Throwable) = delegate.onError(throwable)
    override fun onComplete() = delegate.onComplete()
}
