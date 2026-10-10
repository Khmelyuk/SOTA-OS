package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.shared.*

/** A fresh one-use authentication proof authorizes only its own local credential. */
class LocalCredentialLifecycleService(
    private val authentication: AuthenticationService,
    private val repository: LocalCredentialLifecycleRepository,
    private val encoder: LocalCredentialEncoder,
    private val rights: RightsConstraint,
    private val clock: Clock
) {
    fun rotate(unit: SotaId, challenge: AuthenticationChallenge, proof: CharArray, replacement: CharArray) {
        try {
            val unchanged = proof.contentEquals(replacement)
            val change = authorize(unit, challenge, proof, "rotateCredential")
            require(!unchanged) { "Replacement passphrase must differ from the current passphrase." }
            repository.rotate(change, encoder.encode(replacement))
        } finally {
            proof.fill('\u0000')
            replacement.fill('\u0000')
        }
    }

    fun revoke(unit: SotaId, challenge: AuthenticationChallenge, proof: CharArray) {
        try {
            repository.revoke(authorize(unit, challenge, proof, "revokeCredential"))
        } finally {
            proof.fill('\u0000')
        }
    }

    private fun authorize(unit: SotaId, challenge: AuthenticationChallenge, proof: CharArray,
        operation: String): LocalCredentialChange {
        require(challenge.providerId == "local-passphrase") { "Local credential proof required." }
        val session = authentication.complete(unit, challenge, proof)
        val actor = SubjectRef.Person(session.person)
        rights.check(ProtocolInvocation(actor, "Manage own local authentication", Context("authentication")),
            "P01", operation, actor)
        return LocalCredentialChange(unit, requireNotNull(session.providerSubject), session.person,
            requireNotNull(session.credentialRevision), clock.now())
    }
}
