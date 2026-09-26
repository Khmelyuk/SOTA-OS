package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.security.*
import java.io.IOException
import java.util.UUID

class ProductionRoundTripTest : FunSpec({
    test("AC-13 production roots synchronize after connectivity recovery") {
        ProductionFixture().use { first -> ProductionFixture().use { second ->
            first.enroll(first.policy.copy(importOrigins = setOf(localId, peerId)))
            val receiver = P09Runtime(second.store, LocalSyncIdentity(peerId, setOf(remoteActor)), governance,
                Clock { now }, IdGenerator { UUID.randomUUID().toString() })
            second.repositories.authorities.save(second.authority.copy(scope = second.authority.scope.copy(
                resources = second.authority.scope.resources + "peer:local")))
            val localSigner = Ed25519EventSigner.generate()
            val key = ActorSigningKeyRecord(localActor, "local-key", localSigner.publicKeyEncoded())
            first.runtime.keyProvisioning.enroll(invocation, authorityId, key)
            receiver.keyProvisioning.enroll(invocation, authorityId, key)
            val token = receiver.peerProvisioning.enroll(invocation, authorityId,
                second.policy.copy(peer = localId, actors = setOf(localActor),
                    importOrigins = setOf(localId), exportOrigins = setOf(localId)))
            val template = first.record()
            val record = first.runtime.integrity.sign(template.copy(origin = localId, event = template.event.copy(
                actor = localActor, provenance = template.event.provenance.copy(author = localActor))), localSigner)
            first.runtime.recordLocal(record)
            val offline = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse = throw IOException("offline")
            }
            shouldThrow<IOException> { first.runtime.synchronize(peerId, offline) }
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    peer shouldBe peerId
                    return messages.decodeResponse(receiver.exchange("Bearer $token", messages.encodeRequest(request)))
                }
            }
            first.runtime.synchronize(peerId, transport) shouldBe PeerCheckpoint(1, 1)
            second.repositories.events.findById(record.event.id) shouldBe record.event
        } }
    }
    test("canonical signature survives nested JSON and numeric transport roundtrip") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val template = f.record()
            val record = f.runtime.integrity.sign(template.copy(event = template.event.copy(payload = mapOf(
                "text" to "СОТА", "integer" to 1, "fraction" to 1.25,
                "nested" to mapOf("z" to null, "a" to listOf(true, "value"))
            ))), f.signer)
            f.exchange(token, record).acceptedThrough shouldBe 1
        }
    }
})
