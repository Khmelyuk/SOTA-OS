package sotaos.domain.rights

import sotaos.domain.shared.*
import java.time.Instant

/** A subject-bound constraint; it is distinct from a permission or delegated Authority. */
data class Right(
    val id: RightId,
    val subject: SubjectRef,
    val type: String,
    val scope: Scope,
    val source: String,
    val status: RightStatus,
    val constraints: Set<String>
) {
    init {
        require(type.isNotBlank()) { "Right type must not be blank." }
        require(source.isNotBlank()) { "Right source must not be blank." }
    }
}

enum class RightStatus { ACTIVE, SUSPENDED, REVOKED, CONTESTED }

/**
 * Explicit, purpose-limited consent by a Person to a recipient. `notice` stores
 * the human-readable statement shown at grant time; the intake surface remains
 * responsible for ensuring that grant was clear and voluntary.
 */
data class Consent(
    val id: ConsentId,
    val subject: PersonId,
    val recipient: SubjectRef,
    val purpose: String,
    val context: Context,
    val scope: Scope,
    val notice: String,
    /** Exact affirmative input captured by the consent interface. */
    val affirmation: String,
    val grantedAt: Instant,
    val validUntil: Instant?,
    val revokedAt: Instant?
) {
    init {
        require(purpose.isNotBlank()) { "Consent purpose must not be blank." }
        require(notice.isNotBlank()) { "Consent notice must not be blank." }
        require(affirmation.isNotBlank()) { "Consent must record an affirmative confirmation." }
        require(scope.actions.isNotEmpty()) { "Consent must name at least one specific action." }
        require(validUntil == null || validUntil.isAfter(grantedAt)) {
            "Consent expiry must be later than grant time."
        }
        require(revokedAt == null || !revokedAt.isBefore(grantedAt)) {
            "Consent cannot be revoked before it is granted."
        }
    }

    fun permits(
        requester: SubjectRef,
        requestedPurpose: String,
        requestedContext: Context,
        requestedScope: Scope,
        at: Instant
    ): Boolean =
        recipient == requester &&
            purpose == requestedPurpose &&
            context == requestedContext &&
            scope.covers(requestedScope) &&
            !at.isBefore(grantedAt) &&
            (validUntil == null || at.isBefore(validUntil)) &&
            (revokedAt == null || at.isBefore(revokedAt))
}

/** Consent intake and revocation contract. Grant must follow an explicit human affirmative action. */
interface ConsentProtocol {
    // Explicit protocol fields are part of the existing domain contract.
    @Suppress("LongParameterList")
    fun grant(
        invocation: ProtocolInvocation,
        recipient: SubjectRef,
        purpose: String,
        context: Context,
        scope: Scope,
        notice: String,
        affirmation: String,
        validUntil: Instant? = null
    ): Consent

    fun revoke(invocation: ProtocolInvocation, consent: Consent): Consent

    fun permits(
        subject: PersonId,
        recipient: SubjectRef,
        purpose: String,
        context: Context,
        scope: Scope,
        at: Instant
    ): Boolean
}

/** Fundamental Rights are established through governed sources, not self-service permission grants. */
interface RightsRepository {
    fun save(right: Right): Right
    fun findById(id: RightId): Right?
    fun findBySubject(subject: SubjectRef): List<Right>
}

interface ConsentRepository {
    fun save(consent: Consent): Consent
    fun findById(id: ConsentId): Consent?
    fun findBySubject(subject: PersonId): List<Consent>
}

/** Scope coverage shared by Authority and Consent checks. Empty resources mean unrestricted resources. */
fun Scope.covers(requested: Scope): Boolean =
    actions.containsAll(requested.actions) && when {
        resources.isEmpty() -> true
        requested.resources.isEmpty() -> false
        else -> resources.containsAll(requested.resources)
    }
