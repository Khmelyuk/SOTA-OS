package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.agency.*
import sotaos.domain.relation.*
import sotaos.domain.rights.*
import sotaos.domain.shared.*
import sotaos.test.fakes.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.time.Instant

class ConsentExecutionVerificationTest : FunSpec({
    val at = Instant.parse("2026-08-27T00:00:00Z")
    val actor = SubjectRef.Person(PersonId("operator"))
    val affected = PersonId("affected")
    val context = Context("health", MissionId("mission-1"), "specific care operation")
    val purpose = "coordinate care"
    val action = "share-record"

    data class Scenario(
        val service: MissionActionService,
        val decision: Decision,
        val invocation: ProtocolInvocation,
        val actionCount: MutableList<Action>,
        val eventCount: MutableList<sotaos.domain.memory.Event>
    )

    fun scenario(consentVariant: String? = "valid", affectedPersons: List<PersonId> = listOf(affected)): Scenario {
        val clock = FixedClock(at)
        val ids = SequentialIdGenerator("verify")
        val decisions = InMemoryDecisionRepository()
        val actions = mutableListOf<Action>()
        val events = mutableListOf<sotaos.domain.memory.Event>()
        val authorityRepo = InMemoryAuthorityRepository()
        val authority = Authority(
            AuthorityId("authority"), SubjectRef.Core(CoreId("core")), actor,
            Scope(setOf(action), resources = affectedPersons.map { "person:${it.value}" }.toSet()),
            context, AuthorityBasis(), Validity(at, null), SubjectRef.Core(CoreId("core")), LifecycleState.ACTIVE
        )
        authorityRepo.save(authority)
        val consentRepo = object : sotaos.domain.rights.ConsentRepository {
            private val records = mutableMapOf<ConsentId, Consent>()
            override fun save(consent: Consent): Consent { records[consent.id] = consent; return consent }
            override fun findById(id: ConsentId): Consent? = records[id]
            override fun findBySubject(subject: PersonId): List<Consent> =
                records.values.filter { it.subject == subject }
        }
        if (consentVariant != null) {
            val consentPurpose = if (consentVariant == "purpose") "another purpose" else purpose
            val consentContext =
                if (consentVariant == "context") context.copy(description = "different context") else context
            val consentAction = if (consentVariant == "action") "different-action" else action
            val consentResource =
                if (consentVariant == "resource") "person:someone-else" else "person:${affected.value}"
            val consent = Consent(
                ConsentId("consent"), affected,
                if (consentVariant == "recipient") SubjectRef.Person(PersonId("other-recipient")) else actor,
                consentPurpose, consentContext,
                Scope(setOf(consentAction), setOf(consentResource)), "notice", ConsentService.AFFIRMATION_PHRASE,
                at.minusSeconds(1), null, if (consentVariant == "revoked") at.minusMillis(1) else null
            )
            consentRepo.save(consent)
        }
        val consentService = ConsentService(consentRepo, TestRightsConstraint, clock, ids)
        val service = MissionActionService(
            InMemoryMissionRepository(), decisions, object : ActionRepository {
                override fun save(action: Action): Action { actions.add(action); return action }
            }, InMemoryResultRepository(), authorityRepo, object : EventStore {
                override fun append(event: sotaos.domain.memory.Event): sotaos.domain.memory.Event {
                    events.add(event)
                    return event
                }
                override fun findById(id: EventId): sotaos.domain.memory.Event? = events.find { it.id == id }
                override fun findByActor(actor: SubjectRef): List<sotaos.domain.memory.Event> =
                    events.filter { it.actor == actor }
            }, { _, _, _ -> true }, RightsConstraintDecorator(listOf(NoAgentActionRule)), clock, ids, consentService
        )
        val invocation = ProtocolInvocation(actor, purpose, context)
        val decision = service.decide(invocation, actor, MissionId("mission-1"), authority.id, action, affectedPersons)
        return Scenario(service, decision, invocation, actions, events)
    }

    test("person-affecting action is rejected when consent is absent and writes no Action/Event") {
        val s = scenario(consentVariant = null)
        shouldThrow<ActionNotAuthorizedException> { s.service.execute(s.invocation, s.decision) }
        s.actionCount.size shouldBe 0
        s.eventCount.size shouldBe 0
    }

    listOf("purpose", "context", "action", "resource", "recipient", "revoked").forEach { mismatch ->
        test("consent does not authorize a different $mismatch and writes no Action/Event") {
            val s = scenario(consentVariant = mismatch)
            shouldThrow<ActionNotAuthorizedException> { s.service.execute(s.invocation, s.decision) }
            s.actionCount.size shouldBe 0
            s.eventCount.size shouldBe 0
        }
    }

    test("matching consent permits execution and trace records the checked Person") {
        val s = scenario()
        s.service.execute(s.invocation, s.decision)
        s.actionCount.size shouldBe 1
        s.eventCount.size shouldBe 1
        s.eventCount.single().payload["consentCheckedPersons"] shouldBe listOf(affected.value)
    }

    test("self-affecting action does not demand separate consent from its actor") {
        val s = scenario(consentVariant = null, affectedPersons = listOf((actor as SubjectRef.Person).id))
        s.service.execute(s.invocation, s.decision)
        s.actionCount.size shouldBe 1
    }

    test("decision cannot be executed with a changed purpose") {
        val s = scenario()
        shouldThrow<ActionNotAuthorizedException> {
            s.service.execute(s.invocation.copy(purpose = "another purpose"), s.decision)
        }
    }

    test("consent intake rejects a non-exact affirmation without persisting a consent") {
        val saved = mutableListOf<Consent>()
        val repository = object : sotaos.domain.rights.ConsentRepository {
            override fun save(consent: Consent): Consent { saved.add(consent); return consent }
            override fun findById(id: ConsentId): Consent? = saved.find { it.id == id }
            override fun findBySubject(subject: PersonId): List<Consent> = saved.filter { it.subject == subject }
        }
        val service = ConsentService(repository, TestRightsConstraint, FixedClock(at), SequentialIdGenerator())
        shouldThrow<IllegalArgumentException> {
            service.grant(
                ProtocolInvocation(SubjectRef.Person(affected), purpose, context), actor, purpose, context,
                Scope(setOf(action), setOf("person:${affected.value}")), "specific notice", "I agree"
            )
        }
        saved.size shouldBe 0
    }
})
