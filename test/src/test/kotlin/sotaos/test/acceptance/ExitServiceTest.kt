package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.ExitService
import sotaos.domain.shared.*
import java.time.Instant

class ExitServiceTest : FunSpec({
    val person = SubjectRef.Person(PersonId("person-exit"))
    val now = Instant.parse("2026-09-25T00:00:00Z")
    test("voluntary exit exports data and commits only after both stages") {
        val repository = FakeExitRepository()
        val service = ExitService(repository, Clock { now })
        service.requestExit(person, person)
        shouldThrow<IllegalArgumentException> { service.terminateParticipation(person, person) }
        service.revokeActiveDelegations(person)
        service.closeRelations(person)
        shouldThrow<IllegalArgumentException> { service.terminateParticipation(person, person) }
        service.exportPortableData(person).subject shouldBe person
        service.terminateParticipation(person, person)
        repository.committed.size shouldBe 1
    }
    test("third-party exit is rejected by the self-exit slice") {
        val repository = FakeExitRepository()
        val service = ExitService(repository, Clock { now })
        shouldThrow<IllegalArgumentException> { service.requestExit(person, SubjectRef.Core(CoreId("core"))) }
        repository.committed.size shouldBe 0
    }
})

private class FakeExitRepository : ExitRepository {
    val committed = mutableListOf<ExitRequest>()
    override fun snapshot(subject: SubjectRef) = ExitSnapshot(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        ExitPortableData(emptyList(), emptyList(), emptyList(), emptyList())
    )
    override fun commitExit(request: ExitRequest): ExitReceipt {
        committed += request
        return ExitReceipt(request.subject, request.requestedBy, request.requestedAt, "exit-1")
    }
}
