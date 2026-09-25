package sotaos.application.ports

import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SotaId
import java.time.Instant

/** Provider-neutral, short-lived authentication challenge. Provider data is opaque to SOTA core. */
data class AuthenticationChallenge(
    val id: String,
    val unit: SotaId,
    val providerId: String,
    val subjectHint: String?,
    val instructions: String,
    val verificationUri: String?,
    val expiresAt: Instant
)

data class AuthenticatedPrincipal(val providerId: String, val providerSubject: String)

data class AuthenticatedSession(
    val unit: SotaId,
    val person: PersonId,
    val providerId: String,
    val authenticatedAt: Instant
)

data class LocalCredentialRecord(
    val saltBase64: String,
    val hashBase64: String,
    val iterations: Int,
    val failedAttempts: Int,
    val lockedUntil: Instant?
)

/** Implemented by one adapter per method: local passphrase, OIDC, Diia, e-signature, etc. */
interface AuthenticationProvider {
    val providerId: String
    fun begin(unit: SotaId, subjectHint: String?, now: Instant): AuthenticationChallenge
    fun complete(challenge: AuthenticationChallenge, proof: CharArray, now: Instant): AuthenticatedPrincipal?
}

/** Maps a provider's verified subject to the stable SOTA Person within one Sota unit. */
interface AuthenticationRepository {
    fun findPerson(unit: SotaId, providerId: String, providerSubject: String): PersonId?
    /** Call only after an administrator or provider has independently verified the identity link. */
    fun bind(unit: SotaId, providerId: String, providerSubject: String, person: PersonId, linkedAt: Instant)
    fun findLocalCredential(unit: SotaId, handle: String): LocalCredentialRecord?
    fun enrollLocalCredential(
        unit: SotaId,
        handle: String,
        person: PersonId,
        saltBase64: String,
        hashBase64: String,
        iterations: Int,
        createdAt: Instant
    )
    fun recordLocalFailure(unit: SotaId, handle: String, lockedUntil: Instant)
    fun clearLocalFailures(unit: SotaId, handle: String)
}
