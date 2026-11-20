package sotaos.test.architecture_invariants

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.DecisionRepository
import sotaos.application.services.ActionNotAuthorizedException
import sotaos.application.services.MissionActionService
import sotaos.domain.agency.Decision
import sotaos.domain.agency.Mission
import sotaos.domain.shared.*
import sotaos.test.fakes.*

class DecisionAuthorityOwnerTest : FunSpec({
    test("decide rejects another person's authority before policy evaluation or persistence") {
        val f = AuthorityFixture()
        f.repository.save(f.source)
        val written = mutableListOf<Decision>()
        val decisions = object : DecisionRepository {
            override fun save(decision: Decision): Decision { written.add(decision); return decision }
            override fun findById(id: DecisionId): Decision? = written.find { it.id == id }
        }
        val missions = InMemoryMissionRepository().apply {
            save(Mission(MissionId("mission"), f.issuer, "Work", emptyList(), emptyList(),
                Mode.CREATION, LifecycleState.ACTIVE))
        }
        val service = MissionActionService(missions, decisions, InMemoryActionRepository(),
            InMemoryResultRepository(), f.repository, InMemoryEventStore(), f.service::isValid, f.rights,
            f.clock, SequentialIdGenerator())
        // Unrestricted resources keep this check independent from scope coverage.
        f.repository.save(f.source.copy(scope = Scope(setOf("read"))))
        shouldThrow<ActionNotAuthorizedException> {
            service.decide(testInvocation(f.recipient), f.recipient, MissionId("mission"), f.source.id,
                "read", emptyList())
        }
        written shouldBe emptyList()
        f.checks shouldBe emptyList()
        val own = service.decide(testInvocation(f.holder), f.holder, MissionId("mission"), f.source.id,
            "read", emptyList())
        written shouldBe listOf(own)
        f.checks.single().protocol shouldBe "P05"
    }
})
