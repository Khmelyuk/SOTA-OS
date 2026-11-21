package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.AuthorityLineage
import sotaos.application.services.MissionActionService
import sotaos.domain.agency.*
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

class MissionModeTest : FunSpec({
    test("ordinary and signed Actions retain each Mission mode in SQLite and their execution Event") {
        for (signed in listOf(false, true)) for (mode in Mode.entries) SignedActionFixture().use { f ->
            f.key()
            var service = f.modeService(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", mode)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.reopen()
            service = f.modeService(signed)
            val action = service.execute(f.invocation, decision)
            action.mode shouldBe mode
            f.reopen()
            f.repos.actions.findById(action.id) shouldBe action
            val event = f.repos.events.findByActor(f.actor).single()
            event.payload["mode"] shouldBe mode.name
            if (signed) {
                val record = f.journal.records().single()
                f.runtime().sync.integrity.check(record)
                f.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)) shouldBe true
                val otherMode = Mode.entries.single { it != mode }
                val changed = record.event.copy(payload = record.event.payload + ("mode" to otherMode.name))
                shouldThrow<IllegalArgumentException> { f.runtime().sync.integrity.check(record.copy(event = changed)) }
            } else event.signature shouldBe null
        }
    }

    test("execution reads current Mission mode and a later Mission change does not rewrite Action or Result history") {
        for (signed in listOf(false, true)) SignedActionFixture().use { f ->
            f.key()
            val service = f.modeService(signed)
            val mission = service.formMission(f.invocation, f.actor, "Work", Mode.CREATION)
            val decision = service.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.driver.execute(null, "UPDATE mission SET mode = 'PROTECTION'", 0)
            f.reopen()
            val action = f.modeService(signed).execute(f.invocation, decision)
            action.mode shouldBe Mode.PROTECTION
            f.driver.execute(null, "UPDATE mission SET mode = 'CREATION'", 0)
            f.reopen()
            f.modeService(signed).recordResult(f.invocation, action, Outcome.SUCCESS, "Done").action shouldBe action.id
            f.repos.actions.findById(action.id)?.mode shouldBe Mode.PROTECTION
            f.repos.events.findByActor(f.actor).single { it.type == "ACTION_EXECUTED" }
                .payload["mode"] shouldBe "PROTECTION"
            if (signed) f.journal.records().last().parents shouldBe setOf(f.journal.records().first().event.id)
        }
    }

    test("signed Result rejects a persisted Action mode that contradicts its signed execution evidence") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val action = runtime.execute(f.invocation, f.decision(runtime))
            f.driver.execute(null, "UPDATE action SET mode = 'PROTECTION'", 0)
            f.reopen()
            val changed = requireNotNull(f.repos.actions.findById(action.id))
            shouldThrow<IllegalArgumentException> {
                f.runtime().recordResult(f.invocation, changed, Outcome.SUCCESS, "Changed mode")
            }
            f.count("result") shouldBe 0L
            f.journal.records().size shouldBe 1
            f.driver.execute(null, "UPDATE action SET mode = 'CREATION'", 0)
            f.runtime().recordResult(f.invocation, action, Outcome.SUCCESS, "Original mode").action shouldBe action.id
        }
    }

    test("legacy signed execution records without a mode field remain usable without rewriting history") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val decision = f.decision(runtime)
            val action = f.repos.actions.save(Action(ActionId("legacy"), f.actor, f.authorityId,
                decision.id, f.context, f.now, Mode.CREATION, null))
            val event = Event(EventId("legacy-event"), "ACTION_EXECUTED", f.actor, f.now, f.context,
                f.authorityId.value, decision.id.value, mapOf("actionId" to action.id.value), null,
                Provenance(null, f.actor, f.now), "", null)
            val record = runtime.sync.integrity.sign(SyncRecord(event, SotaId("source")), f.signer)
            runtime.sync.recordLocal(record)
            f.reopen()
            f.runtime().recordResult(f.invocation, action, Outcome.SUCCESS, "Legacy report").action shouldBe action.id
            f.journal.records().first() shouldBe record
            f.journal.records().first().event.payload.containsKey("mode") shouldBe false
        }
    }

    test("Protection execution rolls back all writes on receipt failure and preserves mode on retry") {
        SignedActionFixture().use { f ->
            f.key()
            val runtime = f.runtime()
            val mission = runtime.formMission(f.invocation, f.actor, "Work", Mode.PROTECTION)
            val decision = runtime.decide(f.invocation, f.actor, mission.id, f.authorityId, "execute")
            f.driver.execute(null, "CREATE TRIGGER mode_failure BEFORE INSERT ON verified_sync_record " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END", 0)
            shouldThrow<Exception> { runtime.execute(f.invocation, decision) }
            for (table in listOf("action", "event", "sync_journal", "verified_sync_record")) f.count(table) shouldBe 0L
            f.driver.execute(null, "DROP TRIGGER mode_failure", 0)
            f.reopen()
            f.runtime().execute(f.invocation, decision).mode shouldBe Mode.PROTECTION
            f.journal.records().single().event.payload["mode"] shouldBe "PROTECTION"
        }
    }
})

private fun SignedActionFixture.modeService(signed: Boolean): MissionActionProtocol = if (signed) runtime() else
    MissionActionService(repos.missions, repos.decisions, repos.actions, repos.results, repos.authorities,
        repos.events, AuthorityLineage(repos.authorities)::isValid, TestRightsConstraint,
        Clock { now }, IdGenerator { UUID.randomUUID().toString() })
