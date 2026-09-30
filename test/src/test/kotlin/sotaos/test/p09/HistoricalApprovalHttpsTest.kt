package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09HttpsServer
import sotaos.application.ports.ActorSigningKeyRecord
import sotaos.application.sync.PeerCheckpoint
import sotaos.persistence.SqlDelightRepositories
import sotaos.security.Ed25519EventSigner
import sotaos.security.historicalRecordTarget
import sotaos.test.sync.testTlsContext
import java.net.InetSocketAddress
import java.net.http.HttpClient

class HistoricalApprovalHttpsTest : FunSpec({
    test("new receiver recovers source history over HTTPS only after local exact-record approval") {
        HttpsPilotNode("a").use { a -> HttpsPilotNode("b").use { b ->
            a.enroll(b)
            val token = b.enroll(a)
            val record = a.record("before-rotation")
            val replacement = ActorSigningKeyRecord(a.actor, "replacement", Ed25519EventSigner.generate()
                .publicKeyEncoded())
            listOf(a, b).forEach { node ->
                val repos = SqlDelightRepositories(node.store.database)
                val authority = requireNotNull(repos.authorities.findById(authorityId))
                repos.authorities.save(authority.copy(scope = authority.scope.copy(
                    actions = authority.scope.actions + setOf("key.rotate", "history.approve"),
                    resources = authority.scope.resources + historicalRecordTarget(record))))
                node.runtime.keyProvisioning.rotate(invocation, authorityId, replacement, "key-a")
            }
            val tls = testTlsContext()
            HttpClient.newBuilder().sslContext(tls).build().use { client ->
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    shouldThrow<IllegalStateException> {
                        a.runtime.synchronize(b.id, transport(server, b.id, token, client))
                    }
                    b.journal.records().size shouldBe 0
                    a.journal.checkpoint(b.id) shouldBe PeerCheckpoint()
                }
                b.runtime.historicalApprovals.approve(invocation, authorityId, record,
                    ActorSigningKeyRecord(a.actor, "key-a", a.signer.publicKeyEncoded()), "review:source-archive")
                b.reopen()
                P09HttpsServer(InetSocketAddress("localhost", 0), tls, b.runtime).use { server ->
                    a.runtime.synchronize(b.id, transport(server, b.id, token, client)) shouldBe PeerCheckpoint(1, 1)
                    a.runtime.synchronize(b.id, transport(server, b.id, token, client)) shouldBe PeerCheckpoint(1, 1)
                    b.journal.records() shouldBe listOf(record)
                }
            }
        } }
    }
})
