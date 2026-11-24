package sotaos.test.migration

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.api.exit.P10Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.*
import java.time.Instant
import java.util.UUID

internal val exitPerson = PersonId("leaving-person")
internal val completedTarget = ExitTarget(exitPerson, CoreId("chosen"))
internal val pendingTarget = ExitTarget(exitPerson, CoreId("other"))
internal val unaffectedTarget = ExitTarget(exitPerson, CoreId("unaffected"))
internal val exitInvocation = ProtocolInvocation(SubjectRef.Person(exitPerson),
    "Retain my outstanding responsibilities", Context("exit"))
internal val exitOperator = ProtocolInvocation(SubjectRef.Person(PersonId("operator")),
    "Fulfill retained historical obligation", Context("exit-governance"))
internal val exitClock: Instant = Instant.parse("2026-11-24T00:00:00Z")

internal fun HistoricalFixture.exitVectors(): JsonObject =
    resource("vectors.json", "ee575d004f1bd0bbb6e70e88c044bf9957e42a04e270b5e5ee8105367fb0e468")
        .also { check(it.getValue("fixtureOnly").jsonPrimitive.boolean) }

internal fun HistoricalFixture.completedId() = exitVectors().getValue("completedExitId").jsonPrimitive.content
internal fun HistoricalFixture.pendingId() = exitVectors().getValue("pendingExitId").jsonPrimitive.content
internal fun exitRuntime(store: SqlDelightStore) =
    P10Runtime(store, Clock { exitClock }, IdGenerator { UUID.randomUUID().toString() })

internal fun P10Runtime.preparePending(id: String) {
    service.revokeActiveDelegations(exitInvocation, id)
    service.closeRelations(exitInvocation, id)
    service.settleObligations(exitInvocation, id)
}

internal fun HistoricalFixture.injectExitFailure(table: String) = execute(
    "CREATE TRIGGER migration_exit_failure BEFORE INSERT ON ${identifier(table)} " +
        "BEGIN SELECT RAISE(ABORT, 'injected historical exit failure'); END"
)

internal fun assertUnaffected(store: SqlDelightStore) {
    val scope = SqlDelightExitScopeRepository(store.database)
    scope.hasMembership(unaffectedTarget) shouldBe true
    scope.hasDelegations(unaffectedTarget) shouldBe true
    scope.hasRelations(unaffectedTarget) shouldBe true
    SqlDelightExitInventoryRepository(store.database).obligations(unaffectedTarget).single().state shouldBe
        ObligationState.OPEN
    SqlDelightRepositories(store.database).persons.findById(exitPerson)?.id shouldBe exitPerson
}
