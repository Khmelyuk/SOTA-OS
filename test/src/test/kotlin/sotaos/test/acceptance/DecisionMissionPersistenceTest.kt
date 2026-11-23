package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.ActionNotAuthorizedException
import sotaos.domain.agency.*
import sotaos.domain.shared.*

class DecisionMissionPersistenceTest : FunSpec({
    test("Decision snapshot retains all Mission fields and cannot be overwritten or rebound after reopen") {
        SignedActionFixture().use { f ->
            val mission = f.repos.missions.save(Mission(MissionId("terms"), SubjectRef.Core(CoreId("core")),
                "Дослідження\nЗберегти умови", listOf("Перша ціль", "Друга ціль"),
                listOf(f.actor.id, PersonId("participant")), Mode.PROTECTION, LifecycleState.ACTIVE))
            val decision = f.bindingService(false).decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.reopen()
            f.repos.decisions.findMissionSnapshot(decision.id) shouldBe mission
            shouldThrow<Exception> { f.repos.decisions.save(decision, mission.copy(intent = "Rewritten")) }
            for (change in listOf("mission_snapshot_json = NULL", "mission_snapshot_json = '{}'",
                "intent_ref = 'different'")) {
                shouldThrow<Exception> { f.driver.execute(null, "UPDATE decision SET $change", 0) }
            }
            shouldThrow<IllegalArgumentException> {
                f.repos.decisions.save(decision.copy(id = DecisionId("wrong")), mission.copy(id = MissionId("other")))
            }
            shouldThrow<IllegalArgumentException> {
                f.repos.decisions.save(decision.copy(id = DecisionId("inactive")),
                    mission.copy(state = LifecycleState.COMPLETED))
            }
            f.repos.decisions.findMissionSnapshot(decision.id) shouldBe mission
            f.count("decision") shouldBe 1L
        }
    }

    test("legacy schema migration is repeatable leaves snapshots absent and preserves historical Result reporting") {
        SignedActionFixture().use { f ->
            val service = f.bindingService(false)
            val mission = service.formMission(f.invocation, f.actor, "Historical work", Mode.CREATION)
            val old = f.repos.decisions.save(Decision(DecisionId("old"), f.actor, mission.id.value,
                listOf("execute"), "execute", f.authorityId, f.now, f.invocation.purpose))
            val action = f.repos.actions.save(Action(ActionId("historical"), f.actor, f.authorityId, old.id,
                f.context, f.now, Mode.CREATION, null))
            f.driver.execute(null, "DROP TRIGGER decision_mission_binding_immutable", 0)
            f.driver.execute(null, "ALTER TABLE decision DROP COLUMN mission_snapshot_json", 0)
            repeat(2) { f.reopen() }
            f.repos.decisions.findById(old.id) shouldBe old
            f.repos.decisions.findMissionSnapshot(old.id) shouldBe null
            shouldThrow<Exception> {
                f.driver.execute(null, "UPDATE decision SET mission_snapshot_json = '{}'", 0)
            }
            val reopened = f.bindingService(false)
            shouldThrow<ActionNotAuthorizedException> { reopened.execute(f.invocation, old) }
            reopened.recordResult(f.invocation, action, Outcome.SUCCESS, "Historical report").action shouldBe action.id
            val fresh = reopened.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.repos.decisions.findMissionSnapshot(fresh.id) shouldBe mission
            reopened.execute(f.invocation, fresh).decision shouldBe fresh.id
            f.count("action") shouldBe 2L
            f.count("result") shouldBe 1L
        }
    }

    test("Decision insertion failure leaves no snapshot and succeeds with exact terms after retry") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            val service = f.bindingService(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.PROTECTION)
            f.driver.execute(null, "CREATE TRIGGER decision_failure AFTER INSERT ON decision " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END", 0)
            shouldThrow<Exception> { service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute") }
            f.reopen()
            f.count("decision") shouldBe 0L
            f.count("event") shouldBe 0L
            f.driver.execute(null, "DROP TRIGGER decision_failure", 0)
            val saved = f.bindingService(signed).decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.repos.decisions.findMissionSnapshot(saved.id) shouldBe mission
            f.count("decision") shouldBe 1L
        }
    }

    test("stale Mission denial precedes execution rights and rights denial cannot persist a bound Decision") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            val service = f.bindingService(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            var checks = 0
            val denied = f.bindingService(signed, RightsConstraint {
                checks++
                throw RightsConstraintViolation("DENIED", "Fixture policy")
            })
            shouldThrow<RightsConstraintViolation> {
                denied.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            }
            f.count("decision") shouldBe 0L
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.driver.execute(null, "UPDATE mission SET intent = 'Changed'", 0)
            shouldThrow<ActionNotAuthorizedException> { denied.execute(f.invocation, decision) }
            checks shouldBe 1
            f.count("action") shouldBe 0L
            f.repos.decisions.findMissionSnapshot(decision.id) shouldBe mission
        }
    }

    test("malformed or unsupported stored snapshot fails closed without execution writes") {
        for (snapshot in listOf("{broken", "{}", "{\"snapshotVersion\":2}")) SignedActionFixture().use { f ->
            val service = f.bindingService(false)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            // Simulate out-of-band corruption, bypassing the immutable binding trigger.
            f.driver.execute(null, "DROP TRIGGER decision_mission_binding_immutable", 0)
            f.driver.execute(null, "UPDATE decision SET mission_snapshot_json = ?", 1) { bindString(0, snapshot) }
            f.reopen()
            shouldThrow<Exception> { f.bindingService(false).execute(f.invocation, decision) }
            f.count("action") shouldBe 0L
            f.count("event") shouldBe 0L
        }
    }
})
