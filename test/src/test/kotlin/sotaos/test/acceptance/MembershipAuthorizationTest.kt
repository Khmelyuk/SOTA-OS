package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.MembershipNotAuthorizedException
import sotaos.domain.collective.RoleId
import sotaos.domain.shared.*

class MembershipAuthorizationTest : FunSpec({
    test("removal requires the exact active record and cannot manufacture a different role") {
        SignedActionFixture().use { f ->
            val member = f.collective().accept(f.invocation, f.invitation())
            val authority = f.removalAuthority()
            shouldThrow<IllegalArgumentException> {
                f.collective().remove(f.invocation, member.copy(role = RoleId("forged")), authority.id)
            }
            f.count("membership") shouldBe 1L
            f.collective().remove(f.invocation, member, authority.id).state shouldBe LifecycleState.REVOKED
            f.reopen()
            shouldThrow<IllegalArgumentException> { f.collective().remove(f.invocation, member, authority.id) }
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe null
        }
    }

    test("removal rejects wrong holder collective scope and inactive authorities before rights evaluation") {
        SignedActionFixture().use { f ->
            val member = f.collective().accept(f.invocation, f.invitation())
            val valid = f.removalAuthority()
            val denied = listOf(valid.copy(subject = SubjectRef.Person(PersonId("other"))),
                valid.copy(accountabilityTarget = SubjectRef.Core(CoreId("other"))),
                valid.copy(scope = Scope(setOf("execute"), setOf("core:core"))),
                valid.copy(scope = Scope(setOf("membership.remove"), setOf("core:other"))),
                valid.copy(state = LifecycleState.REVOKED), valid.copy(state = LifecycleState.SUSPENDED),
                valid.copy(validity = Validity(f.now.minusSeconds(2), f.now)),
                valid.copy(validity = Validity(f.now.plusSeconds(1), null)))
            var policies = 0
            val service = f.collective(rights = RightsConstraint {
                policies++; RightsConstraintDecision(setOf("fixture"))
            })
            denied.forEachIndexed { index, authority ->
                val stored = f.repos.authorities.save(authority.copy(id = AuthorityId("denied-$index")))
                shouldThrow<MembershipNotAuthorizedException> { service.remove(f.invocation, member, stored.id) }
                f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe member
            }
            shouldThrow<MembershipNotAuthorizedException> {
                service.remove(f.invocation, member, AuthorityId("unknown"))
            }
            policies shouldBe 0
            val policy = f.collective(rights = RightsConstraint { request ->
                request.authority shouldBe valid
                request.context shouldBe valid.context
                request.scope shouldBe valid.scope
                request.target shouldBe membershipCore
                RightsConstraintDecision(setOf("fixture"))
            })
            policy.remove(f.invocation.copy(context = Context("untrusted")), member, valid.id)
        }
    }

    test("rights denial preserves Core invitation and membership state for every P08 command") {
        SignedActionFixture().use { f ->
            val service = f.collective(rights = RightsConstraint {
                throw RightsConstraintViolation("DENIED", "Fixture policy")
            })
            shouldThrow<RightsConstraintViolation> { service.createCore(f.invocation, "Denied", f.actor.id) }
            shouldThrow<RightsConstraintViolation> {
                service.invite(f.invocation.copy(actor = membershipCore), f.actor.id, membershipCore, null)
            }
            f.count("core") shouldBe 1L
            f.count("membership") shouldBe 0L
            val invitation = f.invitation()
            shouldThrow<RightsConstraintViolation> { service.accept(f.invocation, invitation) }
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe null
            val member = f.collective().accept(f.invocation, invitation)
            shouldThrow<RightsConstraintViolation> { service.leave(f.invocation, member) }
            shouldThrow<RightsConstraintViolation> { service.remove(f.invocation, member, f.removalAuthority().id) }
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe member
            f.count("membership") shouldBe 1L
        }
    }

    test("founder insertion failure rolls back Core creation and the same ID can be retried") {
        SignedActionFixture().use { f ->
            val service = f.collective(ids = IdGenerator { "atomic-core" })
            f.driver.execute(null, "CREATE TRIGGER membership_failure BEFORE INSERT ON membership " +
                "BEGIN SELECT RAISE(ABORT, 'injected founder failure'); END", 0)
            shouldThrow<Exception> { service.createCore(f.invocation, "Atomic", f.actor.id) }
            f.repos.cores.findById(CoreId("atomic-core")) shouldBe null
            f.driver.execute(null, "DROP TRIGGER membership_failure", 0)
            f.reopen()
            val core = f.collective(ids = IdGenerator { "atomic-core" }).createCore(f.invocation, "Atomic", f.actor.id)
            f.repos.memberships.findActiveFor(f.actor.id, SubjectRef.Core(core.id))?.state shouldBe
                LifecycleState.ACTIVE
        }
    }
})
