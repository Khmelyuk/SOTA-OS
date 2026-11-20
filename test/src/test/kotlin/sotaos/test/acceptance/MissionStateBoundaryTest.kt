package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.ActionNotAuthorizedException
import sotaos.application.services.MissionActionService
import sotaos.domain.agency.MissionActionProtocol
import sotaos.domain.agency.Outcome
import sotaos.domain.shared.*
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

class MissionStateBoundaryTest : FunSpec({
    test("valid authority cannot create a decision for an unknown mission or reach rights evaluation") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            var decisionsChecked = 0
            val service = f.missionActions(signed, RightsConstraint {
                if (it.operation == "decide") decisionsChecked++
                RightsConstraintDecision(setOf("fixture"))
            })
            shouldThrow<ActionNotAuthorizedException> {
                service.decide(f.invocation, f.actor, MissionId("unknown"), f.authorityId, "execute")
            }
            decisionsChecked shouldBe 0
            f.count("decision") shouldBe 0L
            f.count("event") shouldBe 0L
        }
    }

    test("all non-active mission states deny new decisions while the authority remains valid") {
        SignedActionFixture().use { f ->
            val runtime = f.runtime()
            val mission = runtime.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            LifecycleState.entries.filterNot { it == LifecycleState.ACTIVE }.forEach { state ->
                f.setMissionState(state)
                shouldThrow<ActionNotAuthorizedException> {
                    runtime.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
                }
            }
            f.count("decision") shouldBe 0L
            f.setMissionState(LifecycleState.ACTIVE)
            runtime.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute").intentRef shouldBe
                mission.id.value
        }
    }

    test("ordinary and signed execution reload mission state after reopen before any Action or journal writes") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            f.key()
            var service = f.missionActions(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            LifecycleState.entries.filterNot { it == LifecycleState.ACTIVE }.forEach { state ->
                f.setMissionState(state)
                f.reopen()
                service = f.missionActions(signed)
                shouldThrow<ActionNotAuthorizedException> { service.execute(f.invocation, decision) }
                for (table in listOf("action", "event", "sync_journal", "verified_sync_record")) {
                    f.count(table) shouldBe 0L
                }
            }
            f.setMissionState(LifecycleState.ACTIVE)
            service.execute(f.invocation, decision).decision shouldBe decision.id
            f.count("action") shouldBe 1L
        }
    }

    test("a persisted decision whose mission is missing cannot execute after database reopen") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            f.key()
            val service = f.missionActions(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.driver.execute(null, "DELETE FROM mission", 0)
            f.reopen()
            shouldThrow<ActionNotAuthorizedException> { f.missionActions(signed).execute(f.invocation, decision) }
            for (table in listOf("action", "event", "sync_journal", "verified_sync_record")) {
                f.count(table) shouldBe 0L
            }
            f.repos.decisions.findById(decision.id) shouldBe decision
        }
    }

    test("decision rights receive the stored mission ID despite a misleading invocation context") {
        SignedActionFixture().use { f ->
            val mission = f.runtime().formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val runtime = f.runtime(rights = RightsConstraint { request ->
                request.mission shouldBe mission.id
                request.context shouldBe f.context
                request.authority?.id shouldBe f.authorityId
                throw RightsConstraintViolation("DENIED", "Mission-specific policy")
            })
            val misleading = f.invocation.copy(context = Context("untrusted", MissionId("different")))
            shouldThrow<RightsConstraintViolation> {
                runtime.decide(misleading, f.actor, mission.id, f.authorityId, "execute")
            }
            f.count("decision") shouldBe 0L
        }
    }

    test("closing a mission stops new execution but does not prevent reporting an already executed Action") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            f.key()
            val service = f.missionActions(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            val action = service.execute(f.invocation, decision)
            f.setMissionState(LifecycleState.COMPLETED)
            val authority = requireNotNull(f.repos.authorities.findById(f.authorityId))
            f.repos.authorities.save(authority.copy(state = LifecycleState.REVOKED))
            f.reopen()
            val reopened = f.missionActions(signed)
            shouldThrow<ActionNotAuthorizedException> { reopened.execute(f.invocation, decision) }
            reopened.recordResult(f.invocation, action, Outcome.SUCCESS, "Delivered before closure").action shouldBe
                action.id
            f.count("action") shouldBe 1L
            f.count("result") shouldBe 1L
        }
    }
})

private fun SignedActionFixture.missionActions(signed: Boolean,
    rights: RightsConstraint = TestRightsConstraint): MissionActionProtocol = if (signed) runtime(rights = rights) else
    MissionActionService(repos.missions, repos.decisions, repos.actions, repos.results, repos.authorities,
        repos.events, { _, _, _ -> true }, rights, Clock { now }, IdGenerator { UUID.randomUUID().toString() })

private fun SignedActionFixture.setMissionState(state: LifecycleState) {
    driver.execute(null, "UPDATE mission SET state = ?", 1) { bindString(0, state.name) }
}
