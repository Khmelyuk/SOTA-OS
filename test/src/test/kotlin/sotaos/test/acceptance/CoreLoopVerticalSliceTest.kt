package sotaos.test.acceptance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import sotaos.application.services.*
import sotaos.domain.agency.Outcome
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.test.fakes.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator

/**
 * Executes MVP Specification §15 "Ключовий MVP сценарій" end to end:
 *
 *   PERSON A -> CORE -> PERSON B joins -> INTENT -> MISSION ->
 *   AUTHORITY(for B) -> DECISION(B) -> ACTION(B) -> RESULT ->
 *   EXPERIENCE -> KNOWLEDGE
 *
 * Each step is also individually labeled with the AC it satisfies, per
 * tests/acceptance/AcceptanceCriteriaMap.md.
 *
 * Runs against in-memory fakes (T4/acceptance tier per ADR-007) — this
 * proves domain + application correctness; the same scenario against
 * real SQLite is the T3 integration follow-up once persistence
 * adapters exist (see persistence/README.md).
 */
class CoreLoopVerticalSliceTest : FunSpec({

    test("full Core Loop: PERSON -> CORE -> MISSION -> AUTHORITY -> ACTION -> RESULT -> EXPERIENCE -> KNOWLEDGE") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()

        val identityService = IdentityService(InMemoryPersonRepository(), InMemoryIdentityRepository(), clock, ids,
            TestRightsConstraint)
        val authorityRepo = InMemoryAuthorityRepository()
        val authorityService = AuthorityService(authorityRepo, clock, ids, TestRightsConstraint)
        val collectiveService = CollectiveService(
            InMemoryCoreRepository(), InMemoryMembershipRepository(), authorityRepo,
            authorityService::isValid, clock, ids, TestRightsConstraint
        )
        val events = InMemoryEventStore()
        val missionActionService = MissionActionService(
            missions = InMemoryMissionRepository(),
            decisions = InMemoryDecisionRepository(),
            actions = InMemoryActionRepository(),
            results = InMemoryResultRepository(),
            authorities = authorityRepo,
            events = events,
            authorityCheck = authorityService::isValid,
            rightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule)),
            clock = clock,
            ids = ids
        )
        val memoryService = MemoryService(InMemoryExperienceRepository(), InMemoryKnowledgeRepository(), clock, ids,
            TestRightsConstraint)

        // Step 1 (AC-01): PERSON A
        val personA = identityService.createPerson()
        personA shouldNotBe null

        // Step 2 (AC-02): A creates CORE, no admin approval required
        val core = collectiveService.createCore(testInvocation(SubjectRef.Person(personA.id)), "Test Core", personA.id)
        core.state shouldBe LifecycleState.ACTIVE

        // Step 3 (AC-03 subset): PERSON B joins
        val personB = identityService.createPerson()
        val invitation = collectiveService.invite(testInvocation(SubjectRef.Core(core.id)), personB.id,
            SubjectRef.Core(core.id), role = null)
        val membershipB = collectiveService.accept(testInvocation(SubjectRef.Person(personB.id)), invitation)
        membershipB.state shouldBe LifecycleState.ACTIVE

        // Step 4-5 (AC-04): INTENT -> MISSION, owned by the Core
        val intent = missionActionService.createIntent(testInvocation(SubjectRef.Core(core.id)),
            SubjectRef.Core(core.id), "Deliver the first working slice")
        val mission = missionActionService.formMission(testInvocation(SubjectRef.Core(core.id)),
            SubjectRef.Core(core.id), intent, Mode.CREATION)
        mission.state shouldBe LifecycleState.ACTIVE

        // Step 6 (AC-05): CORE grants scoped Authority to B for this mission
        val missionContext = Context(domain = "delivery", mission = mission.id)
        val authority = authorityService.grant(
            invocation = testInvocation(SubjectRef.Core(core.id)),
            issuer = SubjectRef.Core(core.id),
            subject = SubjectRef.Person(personB.id),
            scope = Scope(actions = setOf("implement-slice")),
            context = missionContext,
            basis = AuthorityBasis(missionRef = mission.id.value, note = "delegated for mission execution"),
            validity = Validity(from = clock.now(), until = null),
            accountableTo = SubjectRef.Core(core.id)
        )
        authority.state shouldBe LifecycleState.ACTIVE

        // Step 7 (AC-07 part 1): B decides
        val decision = missionActionService.decide(
            invocation = testInvocation(SubjectRef.Person(personB.id)),
            subject = SubjectRef.Person(personB.id),
            mission = mission.id,
            authority = authority.id,
            chosen = "implement-slice",
            affectedPersons = emptyList()
        )

        // Step 8 (AC-07 part 2 / AC-15): B executes — MUST succeed because
        // Authority scope exactly covers the decided action.
        val action = missionActionService.execute(testInvocation(SubjectRef.Person(personB.id)), decision)
        action.authority shouldBe authority.id

        // AC-14: the Action produced an Event with provenance + hash.
        val actorEvents = events.findByActor(SubjectRef.Person(personB.id))
        actorEvents.size shouldBe 1
        actorEvents.first().contentHash.isNotBlank() shouldBe true

        // Step 9 (AC-09): record Result
        val result = missionActionService.recordResult(testInvocation(SubjectRef.Person(personB.id)), action,
            Outcome.SUCCESS, "Slice implemented")
        result.outcome shouldBe Outcome.SUCCESS

        // Step 10-11 (AC-10): Experience from the Event
        val event = actorEvents.first()
        val experience = memoryService.createExperience(
            invocation = testInvocation(SubjectRef.Person(personB.id)),
            fromEvents = listOf(event),
            conclusion = "Delegated authority + event-sourced action works end to end",
            applicability = "Small Core, single mission, MVP scope"
        )
        experience.derivedFromEvents shouldBe listOf(event.id)

        // Step 12-13 (AC-11): Knowledge candidate -> validated by a human
        // (Core), never by an Agent.
        val candidate = memoryService.proposeKnowledgeCandidate(testInvocation(SubjectRef.Person(personB.id)),
            experience, "Core Loop is viable end to end")
        candidate.status shouldBe sotaos.domain.memory.KnowledgeStatus.CANDIDATE

        val validated = memoryService.validateKnowledge(testInvocation(SubjectRef.Core(core.id)), candidate,
            SubjectRef.Core(core.id))
        validated.status shouldBe sotaos.domain.memory.KnowledgeStatus.VALIDATED
    }

    test("AC-06: revoking Authority prevents a subsequent Decision") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val authorityRepo = InMemoryAuthorityRepository()
        val authorityService = AuthorityService(authorityRepo, clock, ids, TestRightsConstraint)
        val missionActionService = MissionActionService(
            InMemoryMissionRepository(), InMemoryDecisionRepository(), InMemoryActionRepository(),
            InMemoryResultRepository(), authorityRepo, InMemoryEventStore(),
            authorityService::isValid, RightsConstraintDecorator(listOf(NoAgentActionRule)), clock, ids
        )

        val core = SubjectRef.Core(CoreId("core-1"))
        val person = SubjectRef.Person(PersonId("person-1"))
        val context = Context(domain = "delivery", mission = MissionId("mission-1"))

        val authority = authorityService.grant(
            invocation = testInvocation(core), issuer = core, subject = person,
            scope = Scope(actions = setOf("do-thing")), context = context,
            basis = AuthorityBasis(), validity = Validity(clock.now(), null),
            accountableTo = core
        )

        val decision = missionActionService.decide(testInvocation(person), person, MissionId("mission-1"),
            authority.id, "do-thing", emptyList())

        // Works before revocation.
        missionActionService.execute(testInvocation(person), decision)

        // Revoke, then the same request is rejected while creating a Decision (AC-06 + AC-15).
        authorityService.revoke(testInvocation(core), authority, "mission complete")
        shouldThrow<ActionNotAuthorizedException> {
            missionActionService.decide(testInvocation(person), person, MissionId("mission-1"), authority.id,
                "do-thing", emptyList())
        }
    }
})
