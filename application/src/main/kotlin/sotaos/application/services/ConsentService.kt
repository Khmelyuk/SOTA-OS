package sotaos.application.services

import sotaos.application.ports.Clock
import sotaos.application.ports.ConsentRepository
import sotaos.application.ports.IdGenerator
import sotaos.application.ports.RightsConstraint
import sotaos.domain.rights.*
import sotaos.domain.shared.*
import java.time.Instant

class ConsentService(
    private val consents: ConsentRepository,
    private val rightsConstraint: RightsConstraint,
    private val clock: Clock,
    private val ids: IdGenerator
) : ConsentProtocol {
    companion object {
        const val AFFIRMATION_PHRASE = "НАДАЮ ЗГОДУ"
    }

    override fun grant(
        invocation: ProtocolInvocation,
        recipient: SubjectRef,
        purpose: String,
        context: Context,
        scope: Scope,
        notice: String,
        affirmation: String,
        validUntil: Instant?
    ): Consent {
        val subject = (invocation.actor as? SubjectRef.Person)?.id
            ?: throw IllegalArgumentException("Only a Person can grant personal consent.")
        require(purpose.isNotBlank() && notice.isNotBlank() && affirmation.isNotBlank()) {
            "Consent purpose, notice, and affirmative confirmation are required."
        }
        require(affirmation == AFFIRMATION_PHRASE) { "Consent requires the explicit affirmation phrase." }
        require(scope.actions.isNotEmpty()) { "Consent must specify at least one action." }
        val now = clock.now()
        require(validUntil == null || validUntil.isAfter(now)) { "Consent expiry must be in the future." }
        rightsConstraint.check(invocation, "CONSENT", "grant", recipient, scope = scope, context = context)
        return consents.save(Consent(ConsentId(ids.next()), subject, recipient, purpose, context,
            scope, notice, affirmation, now, validUntil, null))
    }

    override fun revoke(invocation: ProtocolInvocation, consent: Consent): Consent {
        require(invocation.actor == SubjectRef.Person(consent.subject)) {
            "Only the consenting Person may revoke consent."
        }
        val stored = consents.findById(consent.id) ?: throw NoSuchElementException("Consent does not exist.")
        require(stored.subject == consent.subject) { "Consent subject does not match stored record." }
        require(stored.revokedAt == null) { "Consent is already revoked." }
        rightsConstraint.check(invocation, "CONSENT", "revoke", stored.recipient, scope = stored.scope,
            context = stored.context)
        val revoked = stored.copy(revokedAt = clock.now())
        require(!revoked.revokedAt!!.isBefore(stored.grantedAt)) { "Clock precedes consent grant time." }
        return consents.save(revoked)
    }

    override fun permits(subject: PersonId, recipient: SubjectRef, purpose: String, context: Context, scope: Scope,
        at: Instant): Boolean =
        consents.findBySubject(subject).any { it.permits(recipient, purpose, context, scope, at) }
}
