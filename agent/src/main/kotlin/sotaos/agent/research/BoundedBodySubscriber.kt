package sotaos.agent.research

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Enforce the limit before buffering an untrusted HTTP response. */
internal class BoundedBodySubscriber(private val maximum: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var received = 0L

    override fun getBody(): CompletionStage<ByteArray> = delegate.body
    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        delegate.onSubscribe(value)
    }

    override fun onNext(items: List<ByteBuffer>) {
        received += items.sumOf { it.remaining().toLong() }
        if (received > maximum) {
            subscription.cancel()
            delegate.onError(java.io.IOException("Public response exceeds size limit."))
        } else {
            delegate.onNext(items)
        }
    }

    override fun onError(error: Throwable) = delegate.onError(error)
    override fun onComplete() = delegate.onComplete()
}
