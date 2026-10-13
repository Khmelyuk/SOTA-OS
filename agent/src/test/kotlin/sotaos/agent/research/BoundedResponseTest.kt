package sotaos.agent.research

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow

class BoundedResponseTest : FunSpec({
    test("oversize response is cancelled before accumulating untrusted content") {
        var cancelled = false
        val subscriber = BoundedBodySubscriber(4)
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(count: Long) = Unit
            override fun cancel() { cancelled = true }
        })
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5))))
        cancelled shouldBe true
        shouldThrow<CompletionException> { subscriber.body.toCompletableFuture().join() }
    }
    test("valid response completes at the exact byte limit") {
        val subscriber = BoundedBodySubscriber(4)
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(count: Long) = Unit
            override fun cancel() = Unit
        })
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
        subscriber.onComplete()
        subscriber.body.toCompletableFuture().join().toList() shouldBe listOf<Byte>(1, 2, 3, 4)
    }
})
