package sotaos.api.cli

import sotaos.agent.research.ResearchAgent
import sotaos.agent.research.ResearchAuthorization
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.memory.Event
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightRepositories
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.SyncRecordIntegrity
import java.time.Instant
import java.util.UUID

internal val RESEARCH_CONTEXT = Context("research", description = "Local human-invoked research workspace")
private const val SETUP_EVENT = "RESEARCH_WORKSPACE_SETUP"
private const val AUTHORITY_SECONDS = 86400L

/** Only fresh Core creation has a bootstrap path; login never represents an existing collective. */
internal fun setupResearch(repos: SqlDelightRepositories, session: AuthenticatedSession,
    rights: RightsConstraint): AuthorityId = repos.authorities.transaction {
    val actor = SubjectRef.Person(session.person)
    require(repos.persons.findById(session.person) != null)
    val clock = Clock { Instant.now() }
    val ids = IdGenerator { UUID.randomUUID().toString() }
    val authorityService = AuthorityService(repos.authorities, clock, ids, rights)
    val collective = CollectiveService(repos.cores, repos.memberships, repos.authorities,
        authorityService::isValid, clock, ids, rights)
    val invocation = ProtocolInvocation(actor, "Create a voluntary local research Core", RESEARCH_CONTEXT)
    val core = collective.createCore(invocation, "Local Research Core", session.person)
    val issuer = SubjectRef.Core(core.id)
    require(repos.memberships.findActiveFor(session.person, issuer) != null)
    val authority = authorityService.grant(ProtocolInvocation(issuer, "Founding research delegation", RESEARCH_CONTEXT),
        issuer, actor, Scope(setOf("research.read", "research.ingest", "research.analyze",
            "research.draft", "research.approve"),
            setOf(ResearchAgent.RESOURCE, ResearchAuthorization.unitResource(session.unit))), RESEARCH_CONTEXT,
        AuthorityBasis(note = "Explicit founding confirmation by Person ${session.person.value}"),
        Validity(clock.now(), clock.now().plusSeconds(AUTHORITY_SECONDS)), issuer)
    recordResearchGovernance(repos, session, authority.id, SETUP_EVENT,
        mapOf("coreId" to core.id.value, "providerId" to session.providerId, "unitId" to session.unit.value))
    authority.id
}

/** Voluntary surrender narrows only the authenticated recipient's own bootstrapped grant. */
internal fun surrenderResearch(repos: SqlDelightRepositories, session: AuthenticatedSession,
    id: AuthorityId, rights: RightsConstraint) = repos.authorities.transaction {
    val authority = requireNotNull(repos.authorities.findById(id))
    val actor = SubjectRef.Person(session.person)
    require(authority.subject == actor && authority.context == RESEARCH_CONTEXT)
    val setup = repos.events.findByActor(actor).single { it.type == SETUP_EVENT && it.authorityRef == id.value }
    require(setup.payload["unitId"] == session.unit.value)
    require(authority.issuer == SubjectRef.Core(CoreId(setup.payload.getValue("coreId") as String)))
    rights.evaluate(RightsConstraintRequest(actor, "RESEARCH", "surrender", "Voluntary research authority surrender",
        authority = authority, context = RESEARCH_CONTEXT))
    AuthorityCascade(repos.authorities).revoke(listOf(authority), actor,
        "Authenticated recipient voluntarily surrendered local research authority", Instant.now())
    recordResearchGovernance(repos, session, id, "RESEARCH_AUTHORITY_SURRENDERED", emptyMap())
}

private fun recordResearchGovernance(repos: SqlDelightRepositories, session: AuthenticatedSession,
    authority: AuthorityId, type: String, payload: Map<String, Any?>) {
    val actor = SubjectRef.Person(session.person)
    val time = Instant.now()
    val event = Event(EventId(UUID.randomUUID().toString()), type, actor, time, RESEARCH_CONTEXT,
        authority.value, null, payload, null, Provenance(null, actor, time), "", null)
    val hash = SyncRecordIntegrity(JsonSyncRecordCodec()).hash(SyncRecord(event, session.unit))
    repos.events.append(event.copy(contentHash = hash))
}
