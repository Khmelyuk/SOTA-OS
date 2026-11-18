package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.domain.collective.Membership
import sotaos.domain.collective.RoleId
import sotaos.domain.shared.*

class AdmissionAuthorityTest : FunSpec({
    test("invitation requires exact actor Core recipient unit action context and current authority state") {
        AdmissionFixture().use { f ->
            val root = f.root
            val denied = listOf(root.copy(subject = SubjectRef.Person(f.invitee)),
                root.copy(subject = SubjectRef.Agent(AgentId("agent"))),
                root.copy(issuer = SubjectRef.Core(CoreId("other"))),
                root.copy(issuer = SubjectRef.Person(PersonId("unrecorded-issuer"))),
                root.copy(accountabilityTarget = SubjectRef.Core(CoreId("other"))),
                root.copy(context = Context("other")), root.copy(state = LifecycleState.REVOKED),
                root.copy(state = LifecycleState.SUSPENDED), root.copy(state = LifecycleState.CONTESTED),
                root.copy(validity = Validity(f.credentials.now.minusSeconds(2), f.credentials.now)),
                root.copy(validity = Validity(f.credentials.now.plusSeconds(1), null)),
                root.copy(scope = root.scope.copy(actions = setOf("membership.remove"))),
                root.copy(scope = root.scope.copy(resources = emptySet()))) +
                root.scope.resources.map { missing -> root.copy(scope = root.scope.copy(
                    resources = root.scope.resources - missing)) }
            var policies = 0
            val runtime = f.runtime(rights = RightsConstraint {
                policies++; RightsConstraintDecision(setOf("fixture"))
            })
            denied.forEachIndexed { index, authority ->
                val record = f.repos.authorities.save(authority.copy(id = AuthorityId("denied-$index")))
                shouldThrow<IllegalArgumentException> { f.issue(runtime, f.request.copy(authority = record.id)) }
            }
            policies shouldBe 0
            f.count("membership") shouldBe 0L
            f.count("event") shouldBe 0L
        }
    }

    test("membership and a role cannot substitute for an explicit invitation mandate") {
        AdmissionFixture().use { f ->
            f.repos.memberships.save(Membership(f.credentials.person, f.collective, RoleId("founder"),
                f.credentials.now, null, LifecycleState.ACTIVE))
            shouldThrow<IllegalArgumentException> {
                f.issue(terms = f.request.copy(authority = AuthorityId("unknown")))
            }
            f.count("membership") shouldBe 1L
            f.count("event") shouldBe 0L
        }
    }

    test("recorded delegation is usable but revoking its ancestor prevents acceptance of a pending invitation") {
        AdmissionFixture().use { f ->
            val root = f.root
            val child = f.repos.authorities.save(root.copy(id = AuthorityId("child"), issuer = root.subject,
                subject = SubjectRef.Person(f.invitee), parentAuthorityId = root.id))
            val runtime = f.runtime()
            val invitation = runtime.invite(runtime.begin("invitee"), f.credentials.newPassword.toCharArray(),
                f.request.copy(authority = child.id))
            f.repos.events.findById(invitation.auditEvent)?.actor shouldBe SubjectRef.Person(f.invitee)
            f.repos.authorities.save(root.copy(state = LifecycleState.REVOKED))
            f.credentials.reopen()
            shouldThrow<IllegalArgumentException> { f.accept(f.runtime(), invitation) }
            f.repos.memberships.findRecord(invitation.membership)?.state shouldBe LifecycleState.PROPOSED
            f.count("event") shouldBe 1L
        }
    }

    test("expired mandate inactive Core and unknown recipient deny admission") {
        AdmissionFixture().use { f ->
            val runtime = f.runtime()
            shouldThrow<IllegalArgumentException> { f.issue(runtime, f.request.copy(person = PersonId("unknown"))) }
            val invitation = f.issue(runtime)
            f.credentials.driver.execute(null, "UPDATE core SET state = 'SUSPENDED'", 0)
            shouldThrow<IllegalArgumentException> { f.accept(runtime, invitation) }
            f.credentials.driver.execute(null, "UPDATE core SET state = 'ACTIVE'", 0)
            f.credentials.now = requireNotNull(f.root.validity.until)
            shouldThrow<IllegalArgumentException> { f.accept(runtime, invitation) }
            f.count("event") shouldBe 1L
        }
    }

    test("rights checks retain the authenticated representative mandate and collective admission boundary") {
        AdmissionFixture().use { f ->
            val requests = mutableListOf<RightsConstraintRequest>()
            val runtime = f.runtime(rights = RightsConstraint { request ->
                requests += request
                if (request.actor == f.collective) throw RightsConstraintViolation("CORE_DENIED", "Policy")
                RightsConstraintDecision(setOf("fixture"))
            })
            shouldThrow<RightsConstraintViolation> { f.issue(runtime) }
            requests.first().actor shouldBe SubjectRef.Person(f.credentials.person)
            requests.first().authority shouldBe f.root
            requests.first().scope shouldBe f.root.scope
            requests.first().context shouldBe f.context
            requests.first().target shouldBe f.collective
            requests.last().operation shouldBe "invite"
            f.count("membership") shouldBe 0L
            f.count("event") shouldBe 0L
            val invitation = f.issue()
            val denying = f.runtime(rights = RightsConstraint { request ->
                if (request.actor == SubjectRef.Person(f.invitee)) {
                    throw RightsConstraintViolation("PERSON_DENIED", "Policy")
                }
                RightsConstraintDecision(setOf("fixture"))
            })
            shouldThrow<RightsConstraintViolation> { f.accept(denying, invitation) }
            f.repos.memberships.findRecord(invitation.membership) shouldBe invitation.membership
            f.count("event") shouldBe 1L
        }
    }
})
