package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.memory.*
import sotaos.domain.shared.*

class KnowledgeGovernanceException(message: String) : RuntimeException(message)

/**
 * Implements P06 Event/Provenance + P07 Memory/Knowledge for the
 * vertical slice (AC-10, AC-11).
 *
 * `validateKnowledge` is the enforcement point for AI-09/M-06/AC-16:
 * it rejects any validator that is a SubjectRef.Agent, unconditionally.
 */
class MemoryService(
    private val experiences: ExperienceRepository,
    private val knowledgeRepo: KnowledgeRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val rightsConstraint: RightsConstraint,
    private val events: EventStore,
    private val audit: MemoryAudit = LocalMemoryAudit(events, clock, ids)
) : MemoryKnowledgeProtocol {

    override fun recordEvent(
        invocation: ProtocolInvocation,
        type: String,
        actor: SubjectRef,
        context: Context,
        payload: Map<String, Any?>,
        authorityRef: String?
    ): Event {
        require(invocation.actor == actor) { "Event actor must match the invocation actor." }
        rightsConstraint.check(invocation, "P06", "recordEvent", actor)
        throw UnsupportedOperationException(
            "Event recording for actions goes through MissionActionService.execute(); " +
                "this generic entry point is reserved for non-action events (e.g. " +
                "membership changes) and is not part of the first vertical slice."
        )
    }

    override fun createExperience(invocation: ProtocolInvocation, fromEvents: List<Event>, conclusion: String,
        applicability: String): Experience = knowledgeRepo.transaction {
        require(fromEvents.isNotEmpty()) { "Experience must derive from at least one Event." }
        require(fromEvents.all { it.actor == invocation.actor }) {
            "MVP experience creation is limited to events authored by the invoking subject."
        }
        checkEvents(fromEvents)
        rightsConstraint.check(invocation, "P07", "createExperience", context = fromEvents.first().context)
        val experience = Experience(
            id = ExperienceId(ids.next()),
            derivedFromEvents = fromEvents.map { it.id },
            context = fromEvents.first().context,
            whatWasTried = fromEvents.first().type,
            outcome = fromEvents.last().resultRef ?: "",
            conclusion = conclusion,
            applicabilityConditions = applicability,
            confidence = Confidence.MEDIUM,
            provenance = Provenance(
                sourceEventId = fromEvents.first().id,
                author = fromEvents.first().actor,
                recordedAt = clock.now()
            )
        )
        require(experiences.findById(experience.id) == null) { "Experience ID is already in use." }
        audit.record(invocation, MemoryChange.ExperienceRecorded(experience, fromEvents))
        experiences.save(experience)
    }

    override fun proposeKnowledgeCandidate(invocation: ProtocolInvocation, from: Experience,
        statement: String): Knowledge = knowledgeRepo.transaction {
        require(experiences.findById(from.id) == from) { "Source Experience differs from storage." }
        require(invocation.actor == from.provenance.author) {
            "Knowledge candidate author must match the source Experience author."
        }
        rightsConstraint.check(invocation, "P07", "proposeKnowledgeCandidate", context = from.context)
        val knowledge = Knowledge(
            id = KnowledgeId(ids.next()),
            statement = statement,
            derivedFromExperience = listOf(from.id),
            evidence = emptyList(),
            context = from.context,
            status = KnowledgeStatus.CANDIDATE,
            version = 1,
            supersedes = null,
            provenance = from.provenance
        )
        require(knowledgeRepo.findById(knowledge.id) == null) { "Knowledge ID is already in use." }
        audit.record(invocation, MemoryChange.KnowledgeProposed(knowledge, from))
        knowledgeRepo.save(knowledge)
    }

    override fun reviseKnowledge(invocation: ProtocolInvocation, previous: Knowledge, from: Experience,
        statement: String, reason: String): Knowledge = knowledgeRepo.transaction {
        checkRevision(invocation, previous, from, statement, reason)
        rightsConstraint.check(invocation, "P07", "reviseKnowledge", context = previous.context)
        val revised = Knowledge(KnowledgeId(ids.next()), statement, listOf(from.id), emptyList(), previous.context,
            KnowledgeStatus.CANDIDATE, previous.version + 1, previous.id,
            Provenance(from.provenance.sourceEventId, invocation.actor, clock.now(),
                listOf(previous.id.value, from.id.value)))
        require(knowledgeRepo.findById(revised.id) == null) { "Knowledge ID is already in use." }
        audit.record(invocation, MemoryChange.KnowledgeRevised(revised, previous, from, reason))
        knowledgeRepo.save(revised)
    }

    override fun validateKnowledge(invocation: ProtocolInvocation, candidate: Knowledge,
        validator: SubjectRef): Knowledge = knowledgeRepo.transaction {
        require(invocation.actor == validator) { "Invocation actor must match knowledge validator." }
        if (validator is SubjectRef.Agent) {
            throw KnowledgeGovernanceException(
                "AI-09/M-06 violation: Knowledge canonicalization cannot be performed " +
                    "by an Agent (${validator}). A Person, Core, or Sota principal is required."
            )
        }
        checkKnowledge(candidate)
        require(candidate.status == KnowledgeStatus.CANDIDATE) { "Only a current candidate may be validated." }
        rightsConstraint.check(invocation, "P07", "validateKnowledge", context = candidate.context)
        val validated = candidate.copy(status = KnowledgeStatus.VALIDATED)
        audit.record(invocation, MemoryChange.KnowledgeValidated(validated, candidate))
        knowledgeRepo.save(validated)
    }

    override fun deprecate(invocation: ProtocolInvocation, knowledge: Knowledge, reason: String): Knowledge =
        knowledgeRepo.transaction {
            checkKnowledge(knowledge)
            require(invocation.actor == knowledge.provenance.author) {
                "MVP deprecation is limited to the Knowledge provenance author."
            }
            require(reason.isNotBlank()) { "Deprecation requires an explicit reason." }
            require(knowledge.status in setOf(KnowledgeStatus.CANDIDATE, KnowledgeStatus.VALIDATED)) {
                "Only current candidate or validated Knowledge may be deprecated."
            }
            rightsConstraint.check(invocation, "P07", "deprecate", context = knowledge.context)
            val deprecated = knowledge.copy(status = KnowledgeStatus.DEPRECATED)
            audit.record(invocation, MemoryChange.KnowledgeDeprecated(deprecated, knowledge, reason))
            knowledgeRepo.save(deprecated)
        }

    private fun checkRevision(invocation: ProtocolInvocation, previous: Knowledge, from: Experience,
        statement: String, reason: String) {
        checkKnowledge(previous)
        require(experiences.findById(from.id) == from) { "Source Experience differs from storage." }
        require(invocation.actor == previous.provenance.author && invocation.actor == from.provenance.author) {
            "MVP revision requires the Knowledge and Experience author."
        }
        require(previous.context == from.context) { "Revision source context must match Knowledge." }
        require(statement.isNotBlank() && statement != previous.statement && reason.isNotBlank()) {
            "Revision requires changed nonblank content and an explicit reason."
        }
        require(previous.status in setOf(KnowledgeStatus.CANDIDATE, KnowledgeStatus.VALIDATED,
            KnowledgeStatus.DEPRECATED)) { "Knowledge status is outside the revision lifecycle." }
        knowledgeRepo.checkKnowledgeLineage(previous)
        require(previous.version < Int.MAX_VALUE) { "Knowledge version limit reached." }
        require(knowledgeRepo.findSuccessors(previous.id).isEmpty()) { "Knowledge already has a local successor." }
        val now = clock.now()
        require(!previous.provenance.recordedAt.isAfter(now) && !from.provenance.recordedAt.isAfter(now)) {
            "Revision sources cannot be from the future."
        }
    }

    private fun checkEvents(sources: List<Event>) {
        require(sources.map { it.id }.distinct().size == sources.size) { "Experience requires distinct source Events." }
        require(sources.all { it.context == sources.first().context }) { "Source contexts must match." }
        val now = clock.now()
        sources.forEach { source ->
            require(events.findById(source.id) == source) { "Source Event differs from storage." }
            require(!source.timestamp.isAfter(now)) { "Source Event cannot be from the future." }
        }
    }

    private fun checkKnowledge(value: Knowledge) {
        require(knowledgeRepo.findById(value.id) == value) { "Knowledge differs from its current record." }
    }
}
