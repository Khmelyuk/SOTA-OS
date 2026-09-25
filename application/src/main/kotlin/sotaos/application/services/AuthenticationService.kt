package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.shared.SotaId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AuthenticationDeniedException(message: String) : RuntimeException(message)

/** Provider-neutral authentication boundary with an explicit per-Sota provider allowlist. */
class AuthenticationService(
    providers: Collection<AuthenticationProvider>,
    private val identities: AuthenticationRepository,
    private val providersByUnit: Map<SotaId, Set<String>>,
    private val now: () -> Instant
) {
    private val providers = providers.associateBy { it.providerId }
    private val pending = ConcurrentHashMap<String, AuthenticationChallenge>()

    init {
        require(this.providers.size == providers.size) { "Authentication provider IDs must be unique." }
        require(providersByUnit.values.flatten().all(this.providers::containsKey)) {
            "Every enabled authentication provider must be installed."
        }
    }

    fun begin(unit: SotaId, providerId: String, subjectHint: String? = null): AuthenticationChallenge {
        requireAllowed(unit, providerId)
        val currentTime = now()
        pending.entries.removeIf { !currentTime.isBefore(it.value.expiresAt) }
        val provider = providers.getValue(providerId)
        val supplied = provider.begin(unit, subjectHint, currentTime)
        require(supplied.id.isNotBlank() && supplied.providerId == providerId && supplied.unit == unit) {
            "Provider returned a challenge for a different provider or Sota unit."
        }
        if (pending.putIfAbsent(supplied.id, supplied) != null) {
            throw AuthenticationDeniedException("Provider returned a duplicate authentication challenge ID.")
        }
        return supplied
    }

    fun complete(unit: SotaId, challenge: AuthenticationChallenge, proof: CharArray): AuthenticatedSession {
        try {
            val canonical = consumeChallenge(unit, challenge)
            requireAllowed(unit, challenge.providerId)
            val principal = authenticatePrincipal(canonical, proof)
            val person = identities.findPerson(unit, principal.providerId, principal.providerSubject)
                ?: throw AuthenticationDeniedException("Authenticated identity is not linked in this Sota unit.")
            return AuthenticatedSession(unit, person, principal.providerId, now())
        } finally {
            proof.fill('\u0000')
        }
    }

    private fun consumeChallenge(unit: SotaId, challenge: AuthenticationChallenge): AuthenticationChallenge {
        val canonical = pending.remove(challenge.id)
            ?: throw AuthenticationDeniedException("Authentication challenge is unknown or already used.")
        if (canonical != challenge || challenge.unit != unit || !now().isBefore(challenge.expiresAt)) {
            throw AuthenticationDeniedException("Authentication challenge is invalid or expired.")
        }
        return canonical
    }

    private fun authenticatePrincipal(challenge: AuthenticationChallenge, proof: CharArray): AuthenticatedPrincipal {
        val principal = providers.getValue(challenge.providerId).complete(challenge, proof, now())
            ?: throw AuthenticationDeniedException("Authentication failed.")
        if (principal.providerId != challenge.providerId || principal.providerSubject.isBlank()) {
            throw AuthenticationDeniedException("Authentication provider returned an invalid principal.")
        }
        return principal
    }

    private fun requireAllowed(unit: SotaId, providerId: String) {
        if (providerId !in providersByUnit[unit].orEmpty()) {
            throw AuthenticationDeniedException(
                "Authentication provider '$providerId' is not enabled for Sota unit '${unit.value}'."
            )
        }
    }
}
