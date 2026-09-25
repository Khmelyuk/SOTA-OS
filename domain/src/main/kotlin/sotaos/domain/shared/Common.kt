package sotaos.domain.shared

import java.time.Instant

/**
 * Shared value objects. No entity in this project may substitute a raw
 * String/UUID for these types where an architectural distinction exists
 * (e.g. PersonId vs AccountId) — see ADR baseline "PERSON != ACCOUNT".
 */

@JvmInline
value class PersonId(val value: String)

@JvmInline
value class IdentityId(val value: String)

@JvmInline
value class AccountId(val value: String)

@JvmInline
value class CoreId(val value: String)

@JvmInline
value class SotaId(val value: String)

@JvmInline
value class AgentId(val value: String)

@JvmInline
value class MissionId(val value: String)

@JvmInline
value class TaskId(val value: String)

@JvmInline
value class DecisionId(val value: String)

@JvmInline
value class ActionId(val value: String)

@JvmInline
value class ResultId(val value: String)

@JvmInline
value class EventId(val value: String)

@JvmInline
value class ExperienceId(val value: String)

@JvmInline
value class KnowledgeId(val value: String)

@JvmInline
value class AuthorityId(val value: String)

@JvmInline
value class RightId(val value: String)

@JvmInline
value class ConsentId(val value: String)

@JvmInline
value class EvidenceId(val value: String)

/**
 * A SubjectRef is any entity capable of being an actor, delegator, or
 * delegatee: PERSON, CORE, SOTA, or AGENT. Deliberately NOT a single
 * flat "userId" — this preserves PERSON != USER and CORE/SOTA/AGENT as
 * distinct subject kinds (Architecture Core CORE-01, Data Model §5).
 */
sealed interface SubjectRef {
    data class Person(val id: PersonId) : SubjectRef
    data class Core(val id: CoreId) : SubjectRef
    data class Sota(val id: SotaId) : SubjectRef
    data class Agent(val id: AgentId) : SubjectRef
}

/**
 * Context in which trust/competence/authority/action are evaluated.
 * Never optional — Security Architecture §4: TRUST(A,B) alone is
 * insufficient; TRUST(A->B, CONTEXT, ACTION, SCOPE, TIME) is required.
 */
data class Context(
    val domain: String,
    val mission: MissionId? = null,
    val description: String = ""
)

data class Scope(
    val actions: Set<String>,
    /** Empty means unrestricted resources; a non-empty set is a resource allowlist. */
    val resources: Set<String> = emptySet(),
    val description: String = ""
)

/**
 * Validity window. `until = null` means "revocable but not time-boxed" —
 * NOT "permanent". Permanence is never assumed; see AUTHORITY != UNLIMITED POWER.
 */
data class Validity(
    val from: Instant,
    val until: Instant?
) {
    fun isActiveAt(t: Instant): Boolean =
        !t.isBefore(from) && (until == null || t.isBefore(until))
}

/**
 * Provenance — required on every object that can influence a decision
 * (Data Model §53): KNOWLEDGE, COMPETENCE, TRUST, DECISION, RESULT,
 * AI-generated content.
 */
data class Provenance(
    val sourceEventId: EventId?,
    val author: SubjectRef,
    val recordedAt: Instant,
    val derivedFrom: List<String> = emptyList() // ids of upstream objects
)

/**
 * Generic lifecycle baseline (ADR: derived from Protocol Architecture
 * §18). Entity-specific state machines MAY add transitions but MUST NOT
 * remove PROPOSED/ACTIVE/REVOKED/CONTESTED as reachable states where
 * applicable to that entity.
 */
enum class LifecycleState {
    PROPOSED,
    VERIFIED,
    AUTHORIZED,
    ACTIVE,
    SUSPENDED,
    COMPLETED,
    EVALUATED,
    RECORDED,
    REJECTED,
    REVOKED,
    FAILED,
    ABORTED,
    CONTESTED // ADR-004: divergent sync state, unresolved
}

/** CORE-25 / Data Model §50 — Creation <-> Protection are two modes, not two systems. */
enum class Mode { CREATION, PROTECTION }
