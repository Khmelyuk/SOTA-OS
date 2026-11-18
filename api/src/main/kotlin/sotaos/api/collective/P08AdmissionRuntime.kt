package sotaos.api.collective

import sotaos.application.ports.*
import sotaos.application.services.AuthenticationService
import sotaos.application.services.CollectiveService
import sotaos.domain.collective.Membership
import sotaos.domain.collective.RoleId
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator

data class CoreInvitationRequest(val core: CoreId, val person: PersonId, val role: RoleId?,
    val authority: AuthorityId, val purpose: String)

data class RecordedInvitation(val membership: Membership, val auditEvent: EventId)

/** Fresh local provider proof for each command; no caller-supplied actor or session is accepted. */
class P08AdmissionRuntime(
    private val store: SqlDelightStore,
    private val unit: SotaId,
    private val context: Context,
    clock: Clock,
    ids: IdGenerator,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) {
    private val repositories = SqlDelightRepositories(store.database)
    private val identities = SqlDelightAuthenticationRepository(store.database)
    private val provider = LocalPassphraseAuthenticationProvider(identities)
    private val authentication = AuthenticationService(listOf(provider), identities,
        mapOf(unit to setOf(provider.providerId)), clock::now)
    private val authorization = CoreInvitationAuthorization(repositories, unit, context, clock, rights)
    private val audit = MembershipAdmissionAudit(repositories.events, unit, context, clock, ids)
    private val collective = CollectiveService(repositories.cores, repositories.memberships, repositories.authorities,
        { _, _, _ -> true }, clock, ids, rights)

    fun begin(handle: String): AuthenticationChallenge = store.withLocalAccess {
        authentication.begin(unit, provider.providerId, handle)
    }

    fun invite(challenge: AuthenticationChallenge, proof: CharArray,
        request: CoreInvitationRequest): RecordedInvitation = authenticated(challenge, proof) { session ->
            authorization.authorize(SubjectRef.Person(session.person), request)
            val core = SubjectRef.Core(request.core)
            val invitation = collective.invite(ProtocolInvocation(core, request.purpose, context),
                request.person, core, request.role)
            RecordedInvitation(invitation, audit.append(session, invitation, request.purpose, request.authority))
        }

    fun accept(challenge: AuthenticationChallenge, proof: CharArray, invitation: RecordedInvitation,
        purpose: String): Membership = authenticated(challenge, proof) { session ->
        require(purpose.isNotBlank()) { "Acceptance purpose is required." }
        require(session.person == invitation.membership.subject) { "Only the invited Person may accept." }
        val source = audit.source(invitation)
        val core = requireNotNull(invitation.membership.collective as? SubjectRef.Core)
        // An issued invitation is not an irrevocable admission right: check its mandate again at acceptance.
        authorization.authorize(source.actor as SubjectRef.Person, CoreInvitationRequest(core.id, session.person,
            invitation.membership.role, AuthorityId(requireNotNull(source.authorityRef)),
            source.payload.getValue("purpose") as String))
        val accepted = collective.accept(ProtocolInvocation(SubjectRef.Person(session.person), purpose, context),
            invitation.membership)
        audit.append(session, accepted, purpose, null, source.id)
        accepted
    }

    private fun <T> authenticated(challenge: AuthenticationChallenge, proof: CharArray,
        command: (AuthenticatedSession) -> T): T = try {
        store.withLocalAccess {
            // Keep failed-proof counters outside business rollback; never hold a transaction during user input.
            val session = authentication.complete(unit, challenge, proof)
            store.database.transactionWithResult {
                val handle = requireNotNull(session.providerSubject)
                require(identities.confirmLocalCredential(unit, handle, requireNotNull(session.credentialRevision)))
                require(identities.findPerson(unit, session.providerId, handle) == session.person)
                requireNotNull(repositories.persons.findById(session.person))
                command(session)
            }
        }
    } finally {
        proof.fill('\u0000')
    }
}
