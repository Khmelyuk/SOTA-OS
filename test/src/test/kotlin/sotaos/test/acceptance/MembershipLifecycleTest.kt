package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.Clock
import sotaos.domain.collective.Membership
import sotaos.domain.collective.RoleId
import sotaos.domain.shared.*

class MembershipLifecycleTest : FunSpec({
    test("accept cannot manufacture membership from an unstored invitation") {
        SignedActionFixture().use { f ->
            val forged = Membership(f.actor.id, membershipCore, null, f.now, null, LifecycleState.PROPOSED)
            shouldThrow<IllegalArgumentException> { f.collective().accept(f.invocation, forged) }
            f.count("membership") shouldBe 0L
        }
    }

    test("only the target collective may issue an invitation even with a permissive rights policy") {
        SignedActionFixture().use { f ->
            val unauthorized = listOf(f.actor, SubjectRef.Core(CoreId("other")),
                SubjectRef.Sota(SotaId("other")), SubjectRef.Agent(AgentId("agent")))
            unauthorized.forEach { actor -> shouldThrow<IllegalArgumentException> {
                f.collective().invite(f.invocation.copy(actor = actor), f.actor.id, membershipCore, null)
            } }
            f.count("membership") shouldBe 0L
            for (target in listOf(membershipCore, SubjectRef.Sota(SotaId("sota")))) {
                val invitation = f.collective().invite(f.invocation.copy(actor = target), f.actor.id, target, null)
                f.collective().accept(f.invocation, invitation).collective shouldBe target
            }
            f.count("membership") shouldBe 2L
        }
    }

    test("persisted invitation survives reopen and cannot resurrect membership after acceptance or leave") {
        SignedActionFixture().use { f ->
            val invitation = f.invitation()
            f.count("membership") shouldBe 1L
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe null
            f.reopen()
            val accepted = f.collective().accept(f.invocation, invitation)
            f.count("membership") shouldBe 1L
            shouldThrow<IllegalArgumentException> { f.collective().accept(f.invocation, invitation) }
            val ended = f.collective().leave(f.invocation, accepted)
            ended.state shouldBe LifecycleState.REVOKED
            f.reopen()
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe null
            shouldThrow<IllegalArgumentException> { f.collective().accept(f.invocation, invitation) }
            shouldThrow<IllegalArgumentException> { f.collective().leave(f.invocation, accepted) }
            shouldThrow<IllegalArgumentException> { f.collective().leave(f.invocation, ended) }
            shouldThrow<IllegalArgumentException> { f.invitation() }
            f.count("membership") shouldBe 1L
            val later = f.collective(clock = Clock { f.now.plusSeconds(1) })
            val renewed = later.invite(f.invocation.copy(actor = membershipCore), f.actor.id, membershipCore, null)
            later.accept(f.invocation, renewed).state shouldBe LifecycleState.ACTIVE
            f.count("membership") shouldBe 2L
        }
    }

    test("altered invitation terms and wrong invited Person are denied without new rows") {
        SignedActionFixture().use { f ->
            val invitation = f.invitation()
            val variants = listOf(invitation.copy(role = RoleId("privileged")),
                invitation.copy(start = f.now.minusSeconds(1)),
                invitation.copy(collective = SubjectRef.Core(CoreId("other"))),
                invitation.copy(subject = PersonId("other")),
                invitation.copy(state = LifecycleState.ACTIVE), invitation.copy(end = f.now))
            variants.forEach { altered -> shouldThrow<IllegalArgumentException> {
                f.collective().accept(f.invocation.copy(actor = SubjectRef.Person(altered.subject)), altered)
            } }
            shouldThrow<IllegalArgumentException> {
                f.collective().accept(f.invocation.copy(actor = SubjectRef.Person(PersonId("other"))), invitation)
            }
            f.count("membership") shouldBe 1L
            f.collective().accept(f.invocation, invitation).role shouldBe null
        }
    }

    test("leave cannot manufacture or change membership terms and does not affect another role") {
        SignedActionFixture().use { f ->
            val invented = Membership(f.actor.id, membershipCore, null, f.now, null, LifecycleState.ACTIVE)
            shouldThrow<IllegalArgumentException> { f.collective().leave(f.invocation, invented) }
            val member = f.collective().accept(f.invocation, f.invitation())
            val roleMember = f.collective().accept(f.invocation, f.invitation(RoleId("reviewer")))
            shouldThrow<IllegalArgumentException> {
                f.collective().leave(f.invocation, member.copy(start = f.now.minusSeconds(1)))
            }
            shouldThrow<IllegalArgumentException> {
                f.collective().leave(f.invocation, member.copy(end = f.now))
            }
            f.collective().leave(f.invocation, member)
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe roleMember
            f.count("membership") shouldBe 2L
        }
    }

    test("membership transitions reject a clock before the persisted start") {
        SignedActionFixture().use { f ->
            val invitation = f.invitation()
            val early = f.collective(clock = Clock { f.now.minusSeconds(1) })
            shouldThrow<IllegalArgumentException> { early.accept(f.invocation, invitation) }
            val member = f.collective().accept(f.invocation, invitation)
            shouldThrow<IllegalArgumentException> { early.leave(f.invocation, member) }
            shouldThrow<IllegalArgumentException> { early.remove(f.invocation, member, f.removalAuthority().id) }
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe member
        }
    }
})
