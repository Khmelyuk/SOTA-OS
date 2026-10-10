package sotaos.api.identity

import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.shared.SotaId
import sotaos.persistence.*
import sotaos.security.*

/** Explicit local-passphrase host. Collect terminal secrets outside the shared store gate. */
class P01CredentialsRuntime(
    private val store: SqlDelightStore,
    private val unit: SotaId,
    clock: Clock,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) {
    private val repository = SqlDelightAuthenticationRepository(store.database)
    private val provider = LocalPassphraseAuthenticationProvider(repository)
    private val authentication = AuthenticationService(listOf(provider), repository,
        mapOf(unit to setOf(provider.providerId)), clock::now)
    private val lifecycle = LocalCredentialLifecycleService(authentication,
        SqlDelightLocalCredentialLifecycleRepository(store.database), provider, rights, clock)

    fun begin(handle: String): AuthenticationChallenge = store.withLocalAccess {
        authentication.begin(unit, provider.providerId, handle)
    }

    fun rotate(challenge: AuthenticationChallenge, proof: CharArray, replacement: CharArray) {
        try {
            store.withLocalAccess { lifecycle.rotate(unit, challenge, proof, replacement) }
        } finally {
            proof.fill('\u0000')
            replacement.fill('\u0000')
        }
    }

    fun revoke(challenge: AuthenticationChallenge, proof: CharArray) {
        try {
            store.withLocalAccess { lifecycle.revoke(unit, challenge, proof) }
        } finally {
            proof.fill('\u0000')
        }
    }
}
