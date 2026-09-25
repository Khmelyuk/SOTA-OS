package sotaos.test.architecture_invariants

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import sotaos.application.services.*
import sotaos.domain.agency.Outcome
import sotaos.domain.identity.Credential
import sotaos.domain.memory.KnowledgeStatus
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.test.fakes.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.time.Instant

/**
 * Executable subset of tests/architecture_invariants/InvariantTestPlan.md.
 * See that file for the full plan; tests here are the ones that can be
 * verified against the domain+application layer alone (T1/T2/T4 tier,
 * no real database required).
 */
class NoActionWithoutAuthorityTest : FunSpec({
    test("execute() rejects a Decision whose Authority id does not resolve") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val authorityRepo = InMemoryAuthorityRepository()
        val svc = MissionActionService(
            InMemoryMissionRepository(), InMemoryDecisionRepository(), InMemoryActionRepository(),
            InMemoryResultRepository(), authorityRepo, InMemoryEventStore(),
            { auth, scope, at -> auth.state == LifecycleState.ACTIVE && auth.scope.actions.containsAll(scope.actions) },
            RightsConstraintDecorator(listOf(NoAgentActionRule)), clock, ids
        )
        val ghostDecision = sotaos.domain.agency.Decision(
            id = DecisionId("d-ghost"),
            subject = SubjectRef.Person(PersonId("p-1")),
            intentRef = "m-1",
            optionsConsidered = listOf("x"),
            chosenOption = "x",
            authorityRef = AuthorityId("does-not-exist"),
            timestamp = clock.now()
        )
        shouldThrow<ActionNotAuthorizedException> {
            svc.execute(testInvocation(SubjectRef.Person(PersonId("p-1"))), ghostDecision)
        }
    }

    test("execute() rejects when Authority scope does not cover the requested action") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val authorityRepo = InMemoryAuthorityRepository()
        val authService = AuthorityService(authorityRepo, clock, ids, TestRightsConstraint)
        val svc = MissionActionService(
            InMemoryMissionRepository(), InMemoryDecisionRepository(), InMemoryActionRepository(),
            InMemoryResultRepository(), authorityRepo, InMemoryEventStore(),
            authService::isValid, RightsConstraintDecorator(listOf(NoAgentActionRule)), clock, ids
        )
        val core = SubjectRef.Core(CoreId("core-1"))
        val person = SubjectRef.Person(PersonId("p-1"))
        val context = Context(domain = "d", mission = MissionId("m-1"))
        val authority = authService.grant(
            testInvocation(core), core, person, Scope(actions = setOf("read-only")), context,
            AuthorityBasis(), Validity(clock.now(), null), core
        )
        shouldThrow<ActionNotAuthorizedException> {
            svc.decide(testInvocation(person), person, MissionId("m-1"), authority.id, "delete-everything", emptyList())
        }
    }
})

class EventAppendOnlyTest : FunSpec({
    test("EventStore.append() throws on a duplicate event id (no update path exists)") {
        val store = InMemoryEventStore()
        val e = sotaos.domain.memory.Event(
            id = EventId("e-1"), type = "T", actor = SubjectRef.Person(PersonId("p-1")),
            timestamp = Instant.now(), context = Context(domain = "d"),
            authorityRef = null, decisionRef = null, payload = emptyMap(), resultRef = null,
            provenance = Provenance(null, SubjectRef.Person(PersonId("p-1")), Instant.now()),
            contentHash = "abc", signature = null
        )
        store.append(e)
        shouldThrow<IllegalArgumentException> { store.append(e) }
    }
})

class NoAICanonicalizationTest : FunSpec({
    test("validateKnowledge() rejects a SubjectRef.Agent validator") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val svc = MemoryService(InMemoryExperienceRepository(), InMemoryKnowledgeRepository(), clock, ids,
            TestRightsConstraint)
        val experience = sotaos.domain.memory.Experience(
            id = ExperienceId("exp-1"), derivedFromEvents = listOf(EventId("e-1")),
            context = Context(domain = "d"), whatWasTried = "x", outcome = "y",
            conclusion = "z", applicabilityConditions = "always",
            confidence = sotaos.domain.memory.Confidence.LOW,
            provenance = Provenance(null, SubjectRef.Person(PersonId("p-1")), clock.now())
        )
        val candidate = svc.proposeKnowledgeCandidate(testInvocation(SubjectRef.Person(PersonId("p-1"))),
            experience, "some claim")
        candidate.status shouldBe KnowledgeStatus.CANDIDATE

        shouldThrow<KnowledgeGovernanceException> {
            svc.validateKnowledge(testInvocation(SubjectRef.Agent(AgentId("agent-1"))), candidate,
                SubjectRef.Agent(AgentId("agent-1")))
        }
    }

    test("validateKnowledge() accepts a Person/Core/Sota validator") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val svc = MemoryService(InMemoryExperienceRepository(), InMemoryKnowledgeRepository(), clock, ids,
            TestRightsConstraint)
        val experience = sotaos.domain.memory.Experience(
            id = ExperienceId("exp-2"), derivedFromEvents = listOf(EventId("e-2")),
            context = Context(domain = "d"), whatWasTried = "x", outcome = "y",
            conclusion = "z", applicabilityConditions = "always",
            confidence = sotaos.domain.memory.Confidence.LOW,
            provenance = Provenance(null, SubjectRef.Person(PersonId("p-1")), clock.now())
        )
        val candidate = svc.proposeKnowledgeCandidate(testInvocation(SubjectRef.Person(PersonId("p-1"))),
            experience, "some claim")
        val validated = svc.validateKnowledge(testInvocation(SubjectRef.Person(PersonId("p-1"))), candidate,
            SubjectRef.Person(PersonId("p-1")))
        validated.status shouldBe KnowledgeStatus.VALIDATED
    }
})

class PersonNotAccountTest : FunSpec({
    test("revokeCredential never touches the PersonRepository") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val persons = InMemoryPersonRepository()
        val svc = IdentityService(persons, InMemoryIdentityRepository(), clock, ids, TestRightsConstraint)

        val person = svc.createPerson()
        val credential = Credential(
            id = "cred-1", account = AccountId("acct-1"),
            kind = "keypair", issuedAt = clock.now(), revokedAt = null
        )
        svc.revokeCredential(testInvocation(SubjectRef.Person(person.id)), credential)

        // Person must still be fully intact and unaffected.
        persons.findById(person.id) shouldBe person
    }
})

class AuthorityDelegationCeilingTest : FunSpec({
    test("a Person issuer cannot grant Authority wider than what they hold (no self-escalation)") {
        val clock = FixedClock()
        val ids = SequentialIdGenerator()
        val authorityRepo = InMemoryAuthorityRepository()
        val authService = AuthorityService(authorityRepo, clock, ids, TestRightsConstraint)

        val personIssuer = SubjectRef.Person(PersonId("p-issuer"))
        val personSubject = SubjectRef.Person(PersonId("p-subject"))
        val context = Context(domain = "d", mission = MissionId("m-1"))

        // personIssuer holds nothing yet -> any grant attempt as issuer must fail.
        shouldThrow<AuthorityException> {
            authService.grant(
                testInvocation(personIssuer), personIssuer, personSubject, Scope(actions = setOf("anything")), context,
                AuthorityBasis(), Validity(clock.now(), null), personIssuer
            )
        }
    }
})
