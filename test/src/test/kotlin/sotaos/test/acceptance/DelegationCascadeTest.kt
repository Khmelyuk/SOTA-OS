package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.AuthorityException
import sotaos.domain.shared.*

class DelegationCascadeTest : FunSpec({
    test("P10 revokes all descendants and preserves unrelated and other-Core chains after restart") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            val grandchild = f.delegate(child, delegateC)
            val otherChild = f.delegate(f.authority(f.other), delegateB)
            val independent = f.repos.authorities.save(root.copy(id = AuthorityId("independent"), subject = delegateD))
            val independentChild = f.delegate(independent, delegateC)
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.reopen()
            listOf(root, child, grandchild).forEach {
                f.repos.authorities.findById(it.id)?.state shouldBe LifecycleState.REVOKED
            }
            listOf(otherChild, independent, independentChild).forEach {
                f.repos.authorities.findById(it.id)?.state shouldBe LifecycleState.ACTIVE
            }
            f.repos.authorities.findById(grandchild.id)?.parentAuthorityId shouldBe child.id
            f.scope.hasDelegations(f.target) shouldBe false
            f.revocations().map { it.authority_id }.toSet() shouldBe
                setOf(root.id.value, child.id.value, grandchild.id.value)
            f.revocations().all { it.actor_id == f.person.value && it.reason.contains(id) } shouldBe true
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.revocations().size shouldBe 3
            f.runtime.service.closeRelations(f.invocation, id)
            f.runtime.service.settleObligations(f.invocation, id)
            val archive = f.runtime.service.exportPortableData(f.invocation, id)
            f.runtime.service.terminateParticipation(f.invocation, id, archive.sha256)
            f.scope.hasMembership(f.target) shouldBe false
            f.scope.hasMembership(f.otherTarget) shouldBe true
            shouldThrow<AuthorityException> { f.delegate(grandchild, delegateD) }
        }
    }
    test("pending ancestor exit blocks redelegation before revocation stage") {
        ExitFixture().use { f ->
            val child = f.delegate(f.authority(f.core), delegateB)
            val grandchild = f.delegate(child, delegateC)
            f.runtime.service.requestExit(f.invocation, f.core)
            shouldThrow<Exception> { f.delegate(grandchild, delegateD) }
            f.repos.authorities.findChildren(grandchild.id).size shouldBe 0
            f.delegate(f.authority(f.other), delegateD).state shouldBe LifecycleState.ACTIVE
        }
    }
    test("failed cascade audit rolls back every authority and the P10 transition") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            val grandchild = f.delegate(child, delegateC)
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.failOn("authority_revocation")
            shouldThrow<Exception> { f.runtime.service.revokeActiveDelegations(f.invocation, id) }
            f.exits.find(id)?.stage shouldBe ExitStage.REQUESTED
            listOf(root, child, grandchild).forEach {
                f.repos.authorities.findById(it.id)?.state shouldBe LifecycleState.ACTIVE
            }
            f.revocations().size shouldBe 0
            f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.revocations().size shouldBe 3
        }
    }
    test("failed final stage audit rolls back completed cascade and per-authority audit") {
        ExitFixture().use { f ->
            val child = f.delegate(f.authority(f.core), delegateB)
            val grandchild = f.delegate(child, delegateC)
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.failOn("exit_transition")
            shouldThrow<Exception> { f.runtime.service.revokeActiveDelegations(f.invocation, id) }
            f.repos.authorities.findById(grandchild.id)?.state shouldBe LifecycleState.ACTIVE
            f.revocations().size shouldBe 0
            f.exits.transitions(id).size shouldBe 1
        }
    }
    test("P04 uses stored issuer rejects forged caller fields and revokes subtree atomically") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            val grandchild = f.delegate(child, delegateC)
            val service = f.authorityService()
            shouldThrow<IllegalArgumentException> {
                service.revoke(ProtocolInvocation(delegateD, "forged issuer"), child.copy(issuer = delegateD), "forged")
            }
            service.revoke(f.invocation, child, "withdraw redelegation").state shouldBe LifecycleState.REVOKED
            f.repos.authorities.findById(root.id)?.state shouldBe LifecycleState.ACTIVE
            f.repos.authorities.findById(grandchild.id)?.state shouldBe LifecycleState.REVOKED
            service.revoke(f.invocation, child, "repeat")
            f.revocations().size shouldBe 2
            f.revocations().all { it.cascade_root == child.id.value } shouldBe true
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM authority_revocation", 0) }
        }
    }
})
