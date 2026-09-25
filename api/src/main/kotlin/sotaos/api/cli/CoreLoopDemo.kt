package sotaos.api.cli

import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.application.services.AuthorityService
import sotaos.application.services.CollectiveService
import sotaos.application.services.ConsentService
import sotaos.application.services.IdentityService
import sotaos.application.services.MemoryService
import sotaos.application.services.MissionActionService
import sotaos.domain.agency.Outcome
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.db.SotaOsDatabase
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.time.Instant
import java.util.UUID

private const val DEMO_ACTION = "record-demo-result"
private const val DEMO_AUTHORITY_SECONDS = 3600L

internal fun runCoreLoopDemo(database: SotaOsDatabase) {
    val repositories = SqlDelightRepositories(database)
    val clock = Clock { Instant.now() }
    val ids = IdGenerator { UUID.randomUUID().toString() }

    val rightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
    val identity = IdentityService(repositories.persons, repositories.identities, clock, ids, rightsConstraint)
    val person = identity.createPerson()
    val personInvocation = ProtocolInvocation(SubjectRef.Person(person.id), "Initialize local CLI demo")
    identity.createIdentity(personInvocation, person.id, "cli-founder")

    val authorityService = AuthorityService(repositories.authorities, clock, ids, rightsConstraint)
    val collective = CollectiveService(
        repositories.cores, repositories.memberships, repositories.authorities,
        authorityService::isValid, clock, ids, rightsConstraint
    )
    val coreInvocation = ProtocolInvocation(SubjectRef.Person(person.id), "Create local demo Core")
    val core = collective.createCore(coreInvocation, "SOTA CLI Demo", person.id)
    val context = Context(domain = "local-cli", description = "CLI Core Loop demonstration")
    val authority = authorityService.grant(
        invocation = ProtocolInvocation(SubjectRef.Core(core.id), "Grant scoped demo authority", context),
        issuer = SubjectRef.Core(core.id),
        subject = SubjectRef.Person(person.id),
        scope = Scope(actions = setOf(DEMO_ACTION)),
        context = context,
        basis = AuthorityBasis(note = "Founding Core grants scoped demo authority."),
        validity = Validity(clock.now(), clock.now().plusSeconds(DEMO_AUTHORITY_SECONDS)),
        accountableTo = SubjectRef.Core(core.id)
    )

    val missions = MissionActionService(
        missions = repositories.missions,
        decisions = repositories.decisions,
        actions = repositories.actions,
        results = repositories.results,
        authorities = repositories.authorities,
        events = repositories.events,
        authorityCheck = authorityService::isValid,
        rightsConstraint = rightsConstraint,
        clock = clock,
        ids = ids,
        consent = ConsentService(repositories.consents, rightsConstraint, clock, ids)
    )
    val mission = missions.formMission(ProtocolInvocation(SubjectRef.Core(core.id), "Form local demo mission",
        context), SubjectRef.Core(core.id), "Exercise the local SOTA Core Loop", Mode.CREATION)
    val decision = missions.decide(personInvocation, SubjectRef.Person(person.id), mission.id, authority.id,
        DEMO_ACTION, emptyList())

    val actorRef = SubjectRef.Person(person.id)
    val previousEvents = repositories.events.findByActor(actorRef).mapTo(mutableSetOf()) { it.id }
    val action = missions.execute(personInvocation, decision)
    val result = missions.recordResult(personInvocation, action, Outcome.SUCCESS, "Demo action completed locally.")
    val event = repositories.events.findByActor(actorRef).first { it.id !in previousEvents }

    val experience = recordDemoExperience(repositories, clock, ids, rightsConstraint, personInvocation, event)

    println("Person: ${person.id.value}")
    println("Core: ${core.id.value}")
    println("Mission: ${mission.id.value}")
    println("Decision: ${decision.id.value}")
    println("Action: ${action.id.value}")
    println("Result: ${result.id.value}")
    println("Event: ${event.id.value}")
    println("Experience: ${experience.id.value}")
}

private fun recordDemoExperience(
    repositories: SqlDelightRepositories,
    clock: Clock,
    ids: IdGenerator,
    rightsConstraint: sotaos.application.ports.RightsConstraint,
    personInvocation: ProtocolInvocation,
    event: sotaos.domain.memory.Event
): sotaos.domain.memory.Experience {
    val memory = MemoryService(repositories.experiences, repositories.knowledge, clock, ids, rightsConstraint)
    val experience = memory.createExperience(personInvocation, listOf(event),
        "The local Core Loop completed successfully.",
        "Applicable to a first-run CLI smoke demonstration.")
    memory.proposeKnowledgeCandidate(personInvocation, experience,
        "A local SOTA Core can complete an authorized action offline.")

    return experience
}
