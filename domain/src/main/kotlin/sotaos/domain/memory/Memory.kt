package sotaos.domain.memory

import sotaos.domain.shared.*
import java.time.Instant

/**
 * EVENT — fundamental fact (Architecture Core CORE-16). Append-only
 * (ADR-003). No `update` method exists on this type by design; a
 * correction is a new Event referencing `correctsEventId`.
 */
data class Event(
    val id: EventId,
    val type: String,
    val actor: SubjectRef,
    val timestamp: Instant,
    val context: Context,
    val authorityRef: String?,
    val decisionRef: String?,
    val payload: Map<String, Any?>,
    val resultRef: String?,
    val provenance: Provenance,
    val contentHash: String,
    val signature: String?,          // nullable for R0/R1 actions, ADR-003
    val correctsEventId: EventId? = null
)

/**
 * EXPERIENCE != EVENT. Experience is the interpreted result of practice
 * (Memory Architecture §6-7): context + problem + action + result +
 * evaluation + conclusion + applicability conditions.
 */
data class Experience(
    val id: ExperienceId,
    val derivedFromEvents: List<EventId>,
    val context: Context,
    val whatWasTried: String,
    val outcome: String,
    val conclusion: String,
    val applicabilityConditions: String,
    val confidence: Confidence,
    val provenance: Provenance
)

enum class Confidence { LOW, MEDIUM, HIGH }

/**
 * KNOWLEDGE != EXPERIENCE, != COMPETENCE (Data Model DM-11/DM-12).
 * Canonicality is a governance status, never auto-granted — especially
 * not by an AGENT (Memory Architecture §12, M-06, AI-09).
 */
data class Knowledge(
    val id: KnowledgeId,
    val statement: String,
    val derivedFromExperience: List<ExperienceId>,
    val evidence: List<EvidenceId>,
    val context: Context,
    val status: KnowledgeStatus,
    val version: Int,
    val supersedes: KnowledgeId?,
    val provenance: Provenance
)

enum class KnowledgeStatus {
    OBSERVATION,
    HYPOTHESIS,
    CANDIDATE,          // may be AI-proposed
    VALIDATED,          // requires a human/SOTA validation Event
    CANONICAL,          // requires explicit governance Event; NEVER default
    DEPRECATED,
    SUPERSEDED,
    ARCHIVED
}

data class Evidence(
    val id: EvidenceId,
    val describes: String,   // free-text pointer to what it supports
    val sourceEventId: EventId?,
    val recordedAt: Instant
)

/**
 * P06 + P07 ports.
 *
 * Invariant (AI-09 / M-06, enforced in architecture_invariants):
 * validateKnowledge() may only be called by a SubjectRef.Person or
 * SubjectRef.Sota/Core acting under Authority — never by
 * SubjectRef.Agent. There is no `Agent`-accepting overload of this
 * method anywhere in the codebase.
 */
interface MemoryKnowledgeProtocol {
    fun recordEvent(
        invocation: ProtocolInvocation,
        type: String,
        actor: SubjectRef,
        context: Context,
        payload: Map<String, Any?>,
        authorityRef: String?
    ): Event

    fun createExperience(invocation: ProtocolInvocation, fromEvents: List<Event>, conclusion: String,
        applicability: String): Experience

    fun proposeKnowledgeCandidate(invocation: ProtocolInvocation, from: Experience, statement: String): Knowledge

    /** Only Person/Core/Sota principals accepted — see contract note above. */
    fun validateKnowledge(invocation: ProtocolInvocation, candidate: Knowledge, validator: SubjectRef): Knowledge

    fun deprecate(invocation: ProtocolInvocation, knowledge: Knowledge, reason: String): Knowledge
}
