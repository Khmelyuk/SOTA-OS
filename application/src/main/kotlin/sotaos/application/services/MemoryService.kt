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
    private val rightsConstraint: RightsConstraint
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
        applicability: String): Experience {
        require(fromEvents.isNotEmpty()) { "Experience must derive from at least one Event." }
        require(fromEvents.all { it.actor == invocation.actor }) {
            "MVP experience creation is limited to events authored by the invoking subject."
        }
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
        return experiences.save(experience)
    }

    override fun proposeKnowledgeCandidate(invocation: ProtocolInvocation, from: Experience,
        statement: String): Knowledge {
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
        return knowledgeRepo.save(knowledge)
    }

    override fun validateKnowledge(invocation: ProtocolInvocation, candidate: Knowledge,
        validator: SubjectRef): Knowledge {
        require(invocation.actor == validator) { "Invocation actor must match knowledge validator." }
        if (validator is SubjectRef.Agent) {
            throw KnowledgeGovernanceException(
                "AI-09/M-06 violation: Knowledge canonicalization cannot be performed " +
                    "by an Agent (${validator}). A Person, Core, or Sota principal is required."
            )
        }
        rightsConstraint.check(invocation, "P07", "validateKnowledge", context = candidate.context)
        val validated = candidate.copy(status = KnowledgeStatus.VALIDATED)
        return knowledgeRepo.save(validated)
    }

    override fun deprecate(invocation: ProtocolInvocation, knowledge: Knowledge, reason: String): Knowledge {
        require(invocation.actor == knowledge.provenance.author) {
            "MVP deprecation is limited to the Knowledge provenance author."
        }
        rightsConstraint.check(invocation, "P07", "deprecate", context = knowledge.context)
        val deprecated = knowledge.copy(status = KnowledgeStatus.DEPRECATED)
        return knowledgeRepo.save(deprecated)
    }
}
