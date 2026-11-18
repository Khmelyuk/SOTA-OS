package sotaos.api.collective

import sotaos.application.ports.*
import sotaos.application.services.AuthorityLineage
import sotaos.domain.collective.MembershipAuthorityScope
import sotaos.domain.relation.Authority
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories

/** Called only inside the admission runtime's shared SQLite transaction. */
internal class CoreInvitationAuthorization(
    private val repositories: SqlDelightRepositories,
    private val unit: SotaId,
    private val context: Context,
    private val clock: Clock,
    private val rights: RightsConstraint
) {
    fun authorize(actor: SubjectRef.Person, request: CoreInvitationRequest): Authority {
        require(request.purpose.isNotBlank()) { "Invitation purpose is required." }
        val core = SubjectRef.Core(request.core)
        require(repositories.cores.findById(request.core)?.state == LifecycleState.ACTIVE) { "Core is not active." }
        requireNotNull(repositories.persons.findById(request.person)) { "Invited Person is unknown." }
        val authority = requireNotNull(repositories.authorities.findById(request.authority))
        val scope = Scope(setOf(INVITE_ACTION), setOf(MembershipAuthorityScope.resourceRef(core),
            unitResource(unit), "person:${request.person.value}"))
        require(authority.subject == actor && authority.issuer != actor && authority.accountabilityTarget == core)
        require(authority.context == context && authority.scope.resources.containsAll(scope.resources))
        require(AuthorityLineage(repositories.authorities).isValid(authority, scope, clock.now())) {
            "Invitation requires a current scoped authority and valid ancestry."
        }
        var root = authority
        while (root.parentAuthorityId != null) {
            root = requireNotNull(repositories.authorities.findById(requireNotNull(root.parentAuthorityId)))
        }
        require(root.issuer == core) { "Invitation authority must originate from the target Core." }
        rights.evaluate(RightsConstraintRequest(actor, "P08", INVITE_ACTION, request.purpose,
            authority = authority, context = context, scope = scope, target = core))
        return authority
    }

    companion object {
        const val INVITE_ACTION = "membership.invite"
        fun unitResource(unit: SotaId): String = "membership-unit:${unit.value}"
    }
}
