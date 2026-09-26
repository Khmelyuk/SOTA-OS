package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.ProvisioningAuthorization
import sotaos.domain.shared.*
import sotaos.security.*

class ProvisioningGovernanceTest : FunSpec({
    test("credential rotation invalidates old token and revocation disables current token") {
        ProductionFixture().use { f ->
            val first = f.enroll()
            val authenticator = RegistryPeerAuthenticator(f.peers)
            authenticator.authenticate("Bearer $first") shouldBe peerId
            val second = f.runtime.peerProvisioning.rotate(invocation, authorityId, peerId, 1)
            authenticator.authenticate("Bearer $first") shouldBe null
            authenticator.authenticate("Bearer $second") shouldBe peerId
            shouldThrow<IllegalArgumentException> {
                f.runtime.peerProvisioning.rotate(invocation, authorityId, peerId, 1)
            }
            f.runtime.peerProvisioning.revoke(invocation, authorityId, peerId, 2)
            authenticator.authenticate("Bearer $second") shouldBe null
            shouldThrow<IllegalArgumentException> {
                f.runtime.peerProvisioning.enroll(invocation, authorityId, f.policy)
            }
            f.peers.audit().map { it.operation } shouldBe
                listOf("key.enroll", "peer.enroll", "peer.rotate", "peer.revoke")
            f.peers.audit().all { it.actor == operator && it.authority == authorityId } shouldBe true
        }
    }
    test("expired revoked contested wrong-actor wrong-context and unscoped authority cannot provision") {
        ProductionFixture().use { f ->
            val invalid = listOf(
                f.authority.copy(validity = Validity(now.minusSeconds(10), now)),
                f.authority.copy(state = LifecycleState.REVOKED),
                f.authority.copy(state = LifecycleState.CONTESTED),
                f.authority.copy(subject = localActor),
                f.authority.copy(context = context),
                f.authority.copy(scope = Scope(setOf("peer.rotate"), setOf("peer:remote"))),
                f.authority.copy(scope = Scope(setOf("peer.enroll"))),
                f.authority.copy(issuer = operator)
            )
            invalid.forEach { authority ->
                f.repositories.authorities.save(authority)
                shouldThrow<IllegalArgumentException> {
                    f.runtime.peerProvisioning.enroll(invocation, authorityId, f.policy)
                }
            }
            f.peers.all().size shouldBe 0
            f.peers.audit().size shouldBe 0
        }
    }
    test("self enrollment and unauthorized rotation do not modify credentials or audit") {
        ProductionFixture().use { f ->
            shouldThrow<IllegalArgumentException> {
                f.runtime.peerProvisioning.enroll(invocation, authorityId, f.policy.copy(actors = setOf(operator)))
            }
            val token = f.enroll()
            shouldThrow<IllegalArgumentException> {
                f.runtime.peerProvisioning.rotate(invocation.copy(actor = remoteActor), authorityId, peerId, 1)
            }
            RegistryPeerAuthenticator(f.peers).authenticate("Bearer $token") shouldBe peerId
            f.peers.find(peerId)?.revision shouldBe 1
            f.peers.audit().size shouldBe 2
        }
    }
    test("key self-enrollment stale rotation and unauthorized revocation fail closed") {
        ProductionFixture().use { f ->
            val key = ActorSigningKeyRecord(operator, "self-key", f.signer.publicKeyEncoded())
            shouldThrow<IllegalArgumentException> { f.runtime.keyProvisioning.enroll(invocation, authorityId, key) }
            f.enroll()
            shouldThrow<IllegalArgumentException> {
                f.runtime.keyProvisioning.rotate(invocation, authorityId,
                    key.copy(actor = remoteActor, keyId = "next-key"), "stale-key")
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.keyProvisioning.revoke(invocation.copy(actor = remoteActor), authorityId,
                    remoteActor, "remote-key-1")
            }
            f.repositories.actorSigningKeys.findByActor(remoteActor)?.keyId shouldBe "remote-key-1"
        }
    }
    test("rights denial overrides a valid provisioning authority without mutation") {
        ProductionFixture().use { f ->
            val denied = RightsConstraint { throw RightsConstraintViolation("DENIED", "Policy denies provisioning") }
            val authorization = ProvisioningAuthorization(f.repositories.authorities, Clock { now }, governance, denied)
            val provisioning = PeerProvisioningService(f.peers, authorization, localId)
            shouldThrow<RightsConstraintViolation> { provisioning.enroll(invocation, authorityId, f.policy) }
            f.peers.all().size shouldBe 0
            f.peers.audit().size shouldBe 0
        }
    }

})
