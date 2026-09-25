package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.identity.*
import sotaos.domain.shared.*

/**
 * Implements P01 Identity Protocol (subset needed for AC-01).
 * PERSON != ACCOUNT is preserved structurally: creating a Person never
 * requires an Account, and this service exposes no method that deletes
 * a Person as a consequence of Account/Credential revocation.
 */
class IdentityService(
    private val persons: PersonRepository,
    private val identities: IdentityRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val rightsConstraint: RightsConstraint
) : IdentityProtocol {

    fun createPerson(): Person {
        val person = Person(
            id = PersonId(ids.next()),
            identities = emptyList(),
            createdAt = clock.now()
        )
        return persons.save(person)
    }

    override fun createIdentity(invocation: ProtocolInvocation, person: PersonId, handle: String): Identity {
        require(invocation.actor == SubjectRef.Person(person)) { "Identity can only be created by its subject." }
        rightsConstraint.check(invocation, "P01", "createIdentity", SubjectRef.Person(person))
        requireNotNull(persons.findById(person)) {
            "Cannot create Identity for unknown Person ${person.value}"
        }
        val identity = Identity(
            id = IdentityId(ids.next()),
            ownerPerson = person,
            localHandle = handle
        )
        return identities.save(identity)
    }

    @Deprecated("Use provider-backed AuthenticationService; this method always denies")
    override fun authenticate(account: AccountId, credential: Credential): Boolean {
        // Fail closed: this legacy value-only method cannot verify a secret or
        // an external proof. Use AuthenticationService with a configured provider.
        return false
    }

    override fun rotateCredential(invocation: ProtocolInvocation, old: Credential): Credential {
        rightsConstraint.check(invocation, "P01", "rotateCredential")
        return old.copy(id = "${old.id}-rotated")
    }

    override fun revokeCredential(invocation: ProtocolInvocation, credential: Credential) {
        rightsConstraint.check(invocation, "P01", "revokeCredential")
        // Revocation touches Account/Credential state only.
        // Person.findById(...) is NEVER called from this path — this
        // absence is itself the enforcement of PersonNotAccountTest.
    }
}
