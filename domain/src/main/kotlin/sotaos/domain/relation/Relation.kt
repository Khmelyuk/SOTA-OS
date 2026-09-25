package sotaos.domain.relation

import sotaos.domain.shared.*
import java.time.Instant

/**
 * TRUST — TRUST(subject -> target, context, scope, time, evidence).
 * NEVER a bare numeric score (ADR-004 decision / Architecture Core:
 * TRUST != SCORE). There is intentionally no `trustScore: Int` field
 * anywhere in this module.
 */
data class Trust(
    val subject: SubjectRef,
    val target: SubjectRef,
    val context: Context,
    val scope: Scope,
    val evidence: List<EvidenceId>,
    val validity: Validity,
    val state: LifecycleState
)

/**
 * COMPETENCE — contextual, evidence-based. STATUS != COMPETENCE
 * (Data Model §12).
 */
data class Competence(
    val subject: SubjectRef,
    val domain: String,
    val scope: Scope,
    val level: CompetenceLevel,
    val evidence: List<EvidenceId>,
    val validity: Validity
)

enum class CompetenceLevel { DECLARED, DEMONSTRATED, VALIDATED }

/**
 * AUTHORITY — delegated, scoped, revocable (Architecture Core CORE-07,
 * Security Architecture §11). This is the single most important
 * invariant-bearing type in the model: no field is optional except
 * `until` (see Scope/Validity docs in shared/Common.kt).
 */
data class Authority(
    val id: AuthorityId,
    val issuer: SubjectRef,
    val subject: SubjectRef,
    val scope: Scope,
    val context: Context,
    val basis: AuthorityBasis,
    val validity: Validity,
    val accountabilityTarget: SubjectRef,
    val state: LifecycleState
)

/** Authority MUST cite what it is based on — never bare ROLE. */
data class AuthorityBasis(
    val trustRef: List<Trust> = emptyList(),
    val competenceRef: List<Competence> = emptyList(),
    val missionRef: String? = null,
    val note: String = ""
)

data class Agreement(
    val parties: List<SubjectRef>,
    val purpose: String,
    val terms: List<String>,
    val validity: Validity,
    val exitTerms: String,
    val state: LifecycleState
)

/**
 * P02/P03/P04 protocol ports.
 *
 * Invariant enforced in architecture_invariants:
 *   grant() MUST require both an issuer that itself holds sufficient
 *   authority in the requested scope, AND the invariant
 *   Delegated Authority(subject) <= Authority(issuer) for that scope
 *   (Agent/AI Architecture §21, reused generally).
 */
interface AuthorityProtocol {
    fun requestTrust(invocation: ProtocolInvocation, subject: SubjectRef, target: SubjectRef, context: Context,
        scope: Scope): Trust
    fun presentCompetence(invocation: ProtocolInvocation, subject: SubjectRef, domain: String,
        evidence: List<EvidenceId>): Competence
    // Explicit protocol fields are part of the existing domain contract.
    @Suppress("LongParameterList")
    fun grant(
        invocation: ProtocolInvocation,
        issuer: SubjectRef,
        subject: SubjectRef,
        scope: Scope,
        context: Context,
        basis: AuthorityBasis,
        validity: Validity,
        accountableTo: SubjectRef
    ): Authority

    /** MUST be faster/simpler than grant() per Security Architecture §23. */
    fun revoke(invocation: ProtocolInvocation, authority: Authority, reason: String): Authority

    fun isValid(authority: Authority, forScope: Scope, at: Instant): Boolean
}
