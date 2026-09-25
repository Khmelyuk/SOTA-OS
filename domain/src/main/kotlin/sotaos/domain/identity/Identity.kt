package sotaos.domain.identity

import sotaos.domain.shared.*
import java.time.Instant

/**
 * PERSON is the primary human subject. It MUST NOT be collapsed with
 * ACCOUNT or IDENTITY (Architecture Core CORE-01: PERSON != USER).
 *
 * Invariant enforced by architecture_invariants tests:
 *   deleting/disabling every Account of a Person MUST NOT delete
 *   the Person record.
 */
data class Person(
    val id: PersonId,
    val identities: List<IdentityId>,
    val createdAt: Instant
)

/** Digital representation of a subject. Identifies; does not define. */
data class Identity(
    val id: IdentityId,
    val ownerPerson: PersonId,
    val localHandle: String,
    val externalRefs: List<String> = emptyList()
)

/** Technical access mechanism. Compromise of one Account != compromise of Person. */
data class Account(
    val id: AccountId,
    val identity: IdentityId,
    val status: AccountStatus
)

enum class AccountStatus { ACTIVE, SUSPENDED, REVOKED }

/** Proof of right to use an Account. Rotatable/revocable independently of Identity. */
data class Credential(
    val id: String,
    val account: AccountId,
    val kind: String, // e.g. "keypair", "password-hash" -- concrete scheme is Infra concern
    val issuedAt: Instant,
    val revokedAt: Instant?
)

/**
 * P01 Identity Protocol port — Application layer implements this against
 * Infrastructure; Domain only declares the contract.
 */
interface IdentityProtocol {
    fun createIdentity(invocation: ProtocolInvocation, person: PersonId, handle: String): Identity
    /** Legacy fail-closed entry point; provider-backed authentication uses application AuthenticationService. */
    @Deprecated("Use provider-backed AuthenticationService; this method always denies")
    fun authenticate(account: AccountId, credential: Credential): Boolean
    fun rotateCredential(invocation: ProtocolInvocation, old: Credential): Credential
    fun revokeCredential(invocation: ProtocolInvocation, credential: Credential)
}
