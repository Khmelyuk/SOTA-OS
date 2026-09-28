package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.protocol.*
import sotaos.security.*
import java.io.IOException
import java.util.UUID

class SignedExitTest : FunSpec({
    test("signed exit survives lost acknowledgement and restart without executing remote membership changes") {
        ExitFixture().use { source -> ExitFixture().use { destination ->
            val signer = Ed25519EventSigner.generate()
            source.bootstrapKey(signer)
            destination.bootstrapKey(signer)
            val sourceNode = SotaId("source")
            val destinationNode = SotaId("destination")
            source.bootstrapPeer(destinationNode, sourceNode)
            val token = destination.bootstrapPeer(sourceNode, sourceNode)
            var sender = source.signed(signer, sourceNode)
            var receiver = destination.signed(signer, destinationNode)
            val service = sender.exit.service
            val id = service.requestExit(source.invocation, source.core).id
            service.revokeActiveDelegations(source.invocation, id)
            service.closeRelations(source.invocation, id)
            service.settleObligations(source.invocation, id)
            val document = service.exportPortableData(source.invocation, id)
            service.terminateParticipation(source.invocation, id, document.sha256)
            val records = source.journal().records()
            records.size shouldBe 6
            records.forEachIndexed { index, record ->
                signer.verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
                record.parents shouldBe if (index == 0) emptySet() else setOf(records[index - 1].event.id)
                record.assertion shouldBe null
            }
            val codec = JsonSyncMessageCodec()
            var loseAcknowledgement = true
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    val response = receiver.sync.exchange("Bearer $token", codec.encodeRequest(request))
                    if (loseAcknowledgement) throw IOException("lost acknowledgement")
                    return codec.decodeResponse(response)
                }
            }
            shouldThrow<IOException> { sender.sync.synchronize(destinationNode, transport) }
            source.reopen()
            destination.reopen()
            sender = source.signed(signer, sourceNode)
            receiver = destination.signed(signer, destinationNode)
            loseAcknowledgement = false
            sender.sync.synchronize(destinationNode, transport) shouldBe PeerCheckpoint(6, 6)
            sender.sync.synchronize(destinationNode, transport) shouldBe PeerCheckpoint(6, 6)
            destination.journal().records() shouldBe records
            destination.scope.hasMembership(destination.target) shouldBe true
            source.scope.hasMembership(source.target) shouldBe false
            source.exits.transitions(id).size shouldBe 6
        } }
    }
    test("journal insertion failure rolls back event exit audit and authority revocation") {
        ExitFixture().use { f ->
            val signer = Ed25519EventSigner.generate()
            f.bootstrapKey(signer)
            val service = f.signed(signer).exit.service
            val id = service.requestExit(f.invocation, f.core).id
            f.failOn("sync_journal")
            shouldThrow<Exception> { service.revokeActiveDelegations(f.invocation, id) }
            f.exits.find(id)?.stage shouldBe ExitStage.REQUESTED
            f.exits.transitions(id).size shouldBe 1
            f.journal().records().size shouldBe 1
            f.repos.events.findByActor(f.invocation.actor).size shouldBe 1
            f.scope.hasDelegations(f.target) shouldBe true
            f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
            f.reopen()
            f.signed(signer).exit.service.revokeActiveDelegations(f.invocation, id)
                .stage shouldBe ExitStage.DELEGATIONS_REVOKED
        }
    }
    test("missing mismatched and revoked actor keys fail before any transition commits") {
        ExitFixture().use { f ->
            val signer = Ed25519EventSigner.generate()
            val service = f.signed(signer).exit.service
            shouldThrow<IllegalArgumentException> { service.requestExit(f.invocation, f.core) }
            f.bootstrapKey(Ed25519EventSigner.generate())
            shouldThrow<IllegalArgumentException> { service.requestExit(f.invocation, f.core) }
            f.exits.findOpen(f.target) shouldBe null
            f.journal().records().size shouldBe 0
            f.bootstrapKey(signer)
            val id = service.requestExit(f.invocation, f.core).id
            f.repos.actorSigningKeys.revoke(f.invocation.actor, "exit-key", f.now)
            shouldThrow<IllegalArgumentException> { service.revokeActiveDelegations(f.invocation, id) }
            f.exits.find(id)?.stage shouldBe ExitStage.REQUESTED
            f.scope.hasDelegations(f.target) shouldBe true
        }
    }
    test("restart cannot downgrade a signed exit or change its origin") {
        ExitFixture().use { f ->
            val signer = Ed25519EventSigner.generate()
            f.bootstrapKey(signer)
            val id = f.signed(signer).exit.service.requestExit(f.invocation, f.core).id
            f.reopen()
            shouldThrow<IllegalArgumentException> {
                f.runtime.service.revokeActiveDelegations(f.invocation, id)
            }
            shouldThrow<IllegalArgumentException> {
                f.signed(signer, SotaId("different")).exit.service.revokeActiveDelegations(f.invocation, id)
            }
            f.scope.hasDelegations(f.target) shouldBe true
            f.journal().records().size shouldBe 1
        }
    }
    test("legacy events are never rewritten when enabling signed exit") {
        ExitFixture().use { f ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            val before = f.repos.events.findByActor(f.invocation.actor)
            val signer = Ed25519EventSigner.generate()
            f.bootstrapKey(signer)
            shouldThrow<IllegalArgumentException> {
                f.signed(signer).exit.service.revokeActiveDelegations(f.invocation, id)
            }
            f.repos.events.findByActor(f.invocation.actor) shouldBe before
            f.scope.hasDelegations(f.target) shouldBe true
            f.runtime.service.revokeActiveDelegations(f.invocation, id).stage shouldBe ExitStage.DELEGATIONS_REVOKED
        }
    }
    test("signed records remain subject to peer context policy") {
        ExitFixture().use { f ->
            val signer = Ed25519EventSigner.generate()
            f.bootstrapKey(signer)
            val runtime = f.signed(signer)
            runtime.exit.service.requestExit(f.invocation, f.core)
            val peer = SotaId("destination")
            f.bootstrapPeer(peer, SotaId("source"), Context("unrelated"))
            var contacted = false
            val transport = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    contacted = true
                    error("must reject before network")
                }
            }
            shouldThrow<IllegalArgumentException> { runtime.sync.synchronize(peer, transport) }
            contacted shouldBe false
        }
    }
})

private fun ExitFixture.bootstrapKey(signer: Ed25519EventSigner) {
    // Trusted test setup; production hosts use governed P09 key provisioning.
    repos.actorSigningKeys.save(ActorSigningKeyRecord(invocation.actor, "exit-key", signer.publicKeyEncoded()), now)
}

private fun ExitFixture.journal() = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())

private fun ExitFixture.signed(signer: EventSigner, node: SotaId = SotaId("source")) = SignedP10Runtime(
    store, LocalSyncIdentity(node, setOf(invocation.actor)), Context("governance"), Clock { now },
    IdGenerator { UUID.randomUUID().toString() }, { signer }
)

private fun ExitFixture.bootstrapPeer(peer: SotaId, origin: SotaId,
    context: Context = Context("core-exit", description = "core:${core.value}")): String {
    val token = PeerCredentials.generate()
    SqlDelightPeerTrustRepository(store.database).save(TrustedPeer(PeerTrustPolicy(peer,
        setOf(invocation.actor), setOf(context), SyncDirection.entries.toSet(), setOf(origin), setOf(origin)),
        PeerCredentials.hash(token)))
    return token
}
