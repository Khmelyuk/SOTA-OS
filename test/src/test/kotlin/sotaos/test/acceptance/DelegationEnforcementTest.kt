package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.shared.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.util.UUID

class DelegationEnforcementTest : FunSpec({
    test("P05 rejects a saved decision after its authority ancestor is suspended") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            val leaf = f.delegate(child, delegateC)
            val repositories = f.repos
            val rights = RightsConstraintDecorator(listOf(NoAgentActionRule))
            val service = MissionActionService(repositories.missions, repositories.decisions, repositories.actions,
                repositories.results, repositories.authorities, repositories.events, { _, _, _ -> true },
                rights, Clock { f.now }, IdGenerator { UUID.randomUUID().toString() })
            val collective = SubjectRef.Core(f.core)
            val mission = service.formMission(ProtocolInvocation(collective, "test mission", root.context),
                collective, "check lineage", Mode.CREATION)
            val actor = ProtocolInvocation(delegateC, "execute", root.context)
            val decision = service.decide(actor, delegateC, mission.id, leaf.id, "execute", emptyList())
            repositories.authorities.save(root.copy(state = LifecycleState.SUSPENDED))
            shouldThrow<ActionNotAuthorizedException> { service.execute(actor, decision) }
            repositories.events.findByActor(delegateC).size shouldBe 0
        }
    }
    test("P05 event failure rolls back Action together with the authority-checked transaction") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            val repositories = f.repos
            val service = MissionActionService(repositories.missions, repositories.decisions, repositories.actions,
                repositories.results, repositories.authorities, repositories.events, { _, _, _ -> true },
                RightsConstraintDecorator(listOf(NoAgentActionRule)), Clock { f.now }, IdGenerator { "atomic-id" })
            val collective = SubjectRef.Core(f.core)
            val mission = service.formMission(ProtocolInvocation(collective, "test mission", root.context),
                collective, "atomic action", Mode.CREATION)
            val actor = ProtocolInvocation(delegateB, "execute", root.context)
            val decision = service.decide(actor, delegateB, mission.id, child.id, "execute", emptyList())
            f.failOn("event")
            shouldThrow<Exception> { service.execute(actor, decision) }
            repositories.events.findByActor(delegateB).size shouldBe 0
            f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
            // Retry the same Action ID: an orphaned first insert would cause a primary-key conflict.
            service.execute(actor, decision).id shouldBe ActionId("atomic-id")
            repositories.events.findByActor(delegateB).size shouldBe 1
        }
    }
    test("P09 provisioning checks the complete independently delegated chain") {
        ExitFixture().use { f ->
            val root = f.repos.authorities.save(f.authority(f.core).copy(id = AuthorityId("provision-root"),
                scope = Scope(setOf("peer.enroll"), setOf("peer:new")), context = Context("provision")))
            val leaf = f.delegate(f.delegate(root, delegateB), delegateC)
            val policy = ProvisioningAuthorization(f.repos.authorities, Clock { f.now }, root.context,
                RightsConstraintDecorator(listOf(NoAgentActionRule)))
            val invocation = ProtocolInvocation(delegateC, "provision peer", root.context)
            policy.authorize(invocation, leaf.id, "peer.enroll", "peer:new", emptySet()).actor shouldBe delegateC
            f.repos.authorities.save(root.copy(state = LifecycleState.SUSPENDED))
            shouldThrow<IllegalArgumentException> {
                policy.authorize(invocation, leaf.id, "peer.enroll", "peer:new", emptySet())
            }
        }
    }
    test("P10 obligation fulfillment and P08 removal cannot use suspended-ancestor authority") {
        ExitFixture().use { f ->
            val root = f.repos.authorities.save(f.authority(f.core).copy(id = AuthorityId("govern-root"),
                scope = Scope(setOf("exit.settle", "membership.remove"),
                    setOf("core:chosen", "obligation:obligation-chosen")), context = Context("governance")))
            val leaf = f.delegate(f.delegate(root, delegateB), delegateC)
            f.repos.authorities.save(root.copy(state = LifecycleState.SUSPENDED))
            val invocation = ProtocolInvocation(delegateC, "governance", root.context)
            shouldThrow<IllegalArgumentException> {
                f.runtime.governance.fulfillObligation(invocation, f.target,
                    "obligation-chosen", "evidence", leaf.id)
            }
            f.inventory.obligations(f.target).single().state shouldBe ObligationState.OPEN
            val service = CollectiveService(f.repos.cores, f.repos.memberships, f.repos.authorities,
                { _, _, _ -> true }, Clock { f.now }, IdGenerator { "unused" },
                RightsConstraintDecorator(listOf(NoAgentActionRule)))
            val membership = requireNotNull(f.repos.memberships.findActiveFor(f.person, SubjectRef.Core(f.core)))
            shouldThrow<MembershipNotAuthorizedException> { service.remove(invocation, membership, leaf.id) }
            f.scope.hasMembership(f.target) shouldBe true
        }
    }
    test("SQLite migration preserves pre-lineage authorities without inventing ancestry") {
        ExitFixture().use { f ->
            f.driver.execute(null, """CREATE TABLE legacy_authority (
                authority_id TEXT NOT NULL PRIMARY KEY, issuer_kind TEXT NOT NULL, issuer_id TEXT NOT NULL,
                subject_kind TEXT NOT NULL, subject_id TEXT NOT NULL, scope_json TEXT NOT NULL,
                context_json TEXT NOT NULL, basis_json TEXT NOT NULL, valid_from TEXT NOT NULL, valid_until TEXT,
                accountable_kind TEXT NOT NULL, accountable_id TEXT NOT NULL, state TEXT NOT NULL)""", 0)
            f.driver.execute(null, """INSERT INTO legacy_authority SELECT authority_id, issuer_kind, issuer_id,
                subject_kind, subject_id, scope_json, context_json, basis_json, valid_from, valid_until,
                accountable_kind, accountable_id, state FROM authority""", 0)
            f.driver.execute(null, "DROP TABLE authority", 0)
            f.driver.execute(null, "ALTER TABLE legacy_authority RENAME TO authority", 0)
            f.reopen()
            f.repos.authorities.findById(f.authority(f.core).id) shouldBe f.authority(f.core)
            f.revocations().size shouldBe 0
            f.scope.hasMembership(f.target) shouldBe true
            val child = f.delegate(f.authority(f.core), delegateB)
            f.reopen()
            f.repos.authorities.findById(child.id)?.parentAuthorityId shouldBe f.authority(f.core).id
        }
    }
})
