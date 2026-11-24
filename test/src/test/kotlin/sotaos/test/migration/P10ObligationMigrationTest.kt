package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.ObligationState
import sotaos.domain.shared.AuthorityId
import sotaos.persistence.SqlDelightExitInventoryRepository
import sotaos.persistence.SqlDelightExitRepository

class P10ObligationMigrationTest : FunSpec({
    test("retained historical obligation needs independent fulfillment and audit failure rolls back its resolution") {
        HistoricalFixture(HistoricalSchema.RUNTIME_P10).use { f ->
            val store = f.open()
            val governance = exitRuntime(store).governance
            val authority = AuthorityId("exit-operator")
            val archive = SqlDelightExitRepository(store.database).export(f.completedId())
            val before = f.sql { it.capture() }
            shouldThrow<IllegalArgumentException> {
                governance.fulfillObligation(exitInvocation, completedTarget, "equipment", "review:new", authority)
            }
            shouldThrow<IllegalArgumentException> {
                governance.fulfillObligation(exitOperator, pendingTarget, "equipment", "review:new", authority)
            }
            f.injectExitFailure("exit_governance_audit")
            val failure = shouldThrow<Exception> {
                governance.fulfillObligation(exitOperator, completedTarget, "equipment", "review:new", authority)
            }
            failure.message.orEmpty().contains("injected historical exit failure") shouldBe true
            f.assertPreserved(before)
            f.execute("DROP TRIGGER migration_exit_failure")
            val reopened = f.reopen()
            exitRuntime(reopened).governance.fulfillObligation(exitOperator, completedTarget,
                "equipment", "review:new", authority)
            val finalStore = f.reopen()
            val obligations = SqlDelightExitInventoryRepository(finalStore.database).obligations(completedTarget)
            obligations.single { it.id == "equipment" }.state shouldBe ObligationState.FULFILLED
            obligations.single { it.id == "equipment" }.resolution shouldBe "review:new"
            obligations.single { it.id == "obligation-chosen" }.state shouldBe ObligationState.RETAINED
            SqlDelightExitRepository(finalStore.database).export(f.completedId()) shouldBe archive
            f.sql { it.query("SELECT COUNT(*) FROM exit_governance_audit WHERE evidence = 'review:new'") } shouldBe
                listOf(listOf("1"))
            assertUnaffected(finalStore)
        }
    }
})
