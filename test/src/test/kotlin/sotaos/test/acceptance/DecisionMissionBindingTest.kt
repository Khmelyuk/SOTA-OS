package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.ActionNotAuthorizedException
import sotaos.application.services.AuthorityLineage
import sotaos.application.services.MissionActionService
import sotaos.domain.agency.*
import sotaos.domain.shared.*
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

class DecisionMissionBindingTest : FunSpec({
    test("changing Mission mode after a decision requires a fresh decision in ordinary and signed execution") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            f.key()
            val service = f.bindingService(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.driver.execute(null, "UPDATE mission SET mode = 'PROTECTION'", 0)
            f.reopen()
            val reopened = f.bindingService(signed)
            shouldThrow<ActionNotAuthorizedException> { reopened.execute(f.invocation, decision) }
            f.assertNoBindingExecution()
            val fresh = reopened.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            reopened.execute(f.invocation, fresh).mode shouldBe Mode.PROTECTION
            f.repos.decisions.findById(decision.id) shouldBe decision
        }
    }

    test("changes to Mission owner intent objectives or participants invalidate the earlier decision") {
        for (signed in listOf(false, true)) for (change in listOf("owner_id = 'other'", "intent = 'Other intent'",
            "objectives_json = '[\"New objective\"]'", "participants_json = '[\"new-person\"]'")) {
            SignedActionFixture().use { f ->
                f.key()
                val service = f.bindingService(signed)
                val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
                val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
                f.driver.execute(null, "UPDATE mission SET $change", 0)
                f.reopen()
                shouldThrow<ActionNotAuthorizedException> { f.bindingService(signed).execute(f.invocation, decision) }
                f.assertNoBindingExecution()
            }
        }
    }

    test("a legacy unbound Decision remains readable but cannot authorize a new execution") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            f.key()
            val service = f.bindingService(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val old = f.repos.decisions.save(Decision(DecisionId("old"), f.actor, mission.id.value,
                listOf("execute"), "execute", f.authorityId, f.now, f.invocation.purpose))
            f.reopen()
            f.repos.decisions.findById(old.id) shouldBe old
            shouldThrow<ActionNotAuthorizedException> { f.bindingService(signed).execute(f.invocation, old) }
            f.assertNoBindingExecution()
        }
    }

    test("signed Action binds the complete decided Mission snapshot and retains it after later changes") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val mission = runtime.formMission(f.invocation, f.actor, "Work", Mode.PROTECTION)
            val decision = runtime.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            val action = runtime.execute(f.invocation, decision)
            val record = f.journal.records().single()
            val snapshot = record.event.payload["missionSnapshot"] as? Map<*, *>
            snapshot?.get("id") shouldBe mission.id.value
            snapshot?.get("mode") shouldBe "PROTECTION"
            snapshot?.get("intent") shouldBe "Work"
            snapshot?.get("ownerId") shouldBe f.actor.id.value
            snapshot?.get("snapshotVersion") shouldBe 1L
            snapshot?.get("ownerKind") shouldBe "PERSON"
            snapshot?.get("objectives") shouldBe emptyList<String>()
            snapshot?.get("participants") shouldBe emptyList<String>()
            snapshot?.get("state") shouldBe "ACTIVE"
            val changed = record.event.copy(payload = record.event.payload +
                ("missionSnapshot" to emptyMap<String, Any>()))
            shouldThrow<IllegalArgumentException> { runtime.sync.integrity.check(record.copy(event = changed)) }
            f.driver.execute(null, "UPDATE mission SET mode = 'CREATION', intent = 'Changed later'", 0)
            f.reopen()
            f.runtime().recordResult(f.invocation, action, Outcome.SUCCESS, "Historical result").action shouldBe
                action.id
            f.journal.records().first() shouldBe record
        }
    }
})

internal fun SignedActionFixture.bindingService(signed: Boolean,
    rights: RightsConstraint = TestRightsConstraint): MissionActionProtocol = if (signed) runtime(rights = rights) else
    MissionActionService(repos.missions, repos.decisions, repos.actions, repos.results, repos.authorities,
        repos.events, AuthorityLineage(repos.authorities)::isValid, rights,
        Clock { now }, IdGenerator { UUID.randomUUID().toString() })

private fun SignedActionFixture.assertNoBindingExecution() {
    for (table in listOf("action", "event", "sync_journal", "verified_sync_record")) count(table) shouldBe 0L
}
