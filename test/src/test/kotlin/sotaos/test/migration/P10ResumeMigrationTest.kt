package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.*

class P10ResumeMigrationTest : FunSpec({
    test("historical pending exit resumes once without changing completed exit or unrelated Core") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val store = f.open()
            val exits = SqlDelightExitRepository(store.database)
            val original = requireNotNull(exits.find(f.pendingId()))
            val archived = exits.export(f.completedId())
            val runtime = exitRuntime(store)
            runtime.service.requestExit(exitInvocation, pendingTarget.core) shouldBe original
            shouldThrow<IllegalArgumentException> {
                runtime.service.revokeActiveDelegations(exitInvocation.copy(actor =
                    SubjectRef.Person(PersonId("intruder"))), original.id)
            }
            repeat(2) { runtime.preparePending(original.id) }
            val document = runtime.service.exportPortableData(exitInvocation, original.id)
            shouldThrow<IllegalArgumentException> {
                runtime.service.terminateParticipation(exitInvocation, original.id, "wrong-digest")
            }
            SqlDelightExitScopeRepository(store.database).hasMembership(pendingTarget) shouldBe true
            val reopened = f.reopen()
            repeat(2) {
                exitRuntime(reopened).service.terminateParticipation(exitInvocation, original.id, document.sha256)
            }
            val finalExits = SqlDelightExitRepository(reopened.database)
            finalExits.transitions(original.id).map { it.stage } shouldBe ExitStage.entries
            finalExits.find(original.id)?.requestedAt shouldBe original.requestedAt
            finalExits.find(original.id)?.updatedAt shouldBe exitClock
            finalExits.export(f.completedId()) shouldBe archived
            SqlDelightExitScopeRepository(reopened.database).hasMembership(pendingTarget) shouldBe false
            SqlDelightExitInventoryRepository(reopened.database).obligations(pendingTarget).single().state shouldBe
                ObligationState.RETAINED
            assertUnaffected(reopened)
        }
    }

    test("archive failure preserves migrated state and restart retries without duplicate history") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val store = f.open()
            val runtime = exitRuntime(store)
            runtime.preparePending(f.pendingId())
            val before = f.sql { it.capture() }
            f.injectExitFailure("exit_archive")
            val failure = shouldThrow<Exception> { runtime.service.exportPortableData(exitInvocation, f.pendingId()) }
            failure.message.orEmpty().contains("injected historical exit failure") shouldBe true
            f.assertPreserved(before)
            f.execute("DROP TRIGGER migration_exit_failure")
            val reopened = f.reopen()
            val service = exitRuntime(reopened).service
            val document = service.exportPortableData(exitInvocation, f.pendingId())
            service.exportPortableData(exitInvocation, f.pendingId()) shouldBe document
            val exits = SqlDelightExitRepository(reopened.database)
            exits.transitions(f.pendingId()).map { it.stage } shouldBe ExitStage.entries.dropLast(1)
            SqlDelightExitScopeRepository(reopened.database).hasMembership(pendingTarget) shouldBe true
            assertUnaffected(reopened)
        }
    }

    test("event insertion failure rolls back migrated membership termination and retry commits once") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val store = f.open()
            val runtime = exitRuntime(store)
            runtime.preparePending(f.pendingId())
            val document = runtime.service.exportPortableData(exitInvocation, f.pendingId())
            val before = f.sql { it.capture() }
            f.injectExitFailure("event")
            val failure = shouldThrow<Exception> {
                runtime.service.terminateParticipation(exitInvocation, f.pendingId(), document.sha256)
            }
            failure.message.orEmpty().contains("injected historical exit failure") shouldBe true
            f.assertPreserved(before)
            f.execute("DROP TRIGGER migration_exit_failure")
            val reopened = f.reopen()
            val service = exitRuntime(reopened).service
            repeat(2) { service.terminateParticipation(exitInvocation, f.pendingId(), document.sha256) }
            SqlDelightExitRepository(reopened.database).transitions(f.pendingId()).size shouldBe 6
            SqlDelightExitScopeRepository(reopened.database).hasMembership(pendingTarget) shouldBe false
            assertUnaffected(reopened)
        }
    }
})
