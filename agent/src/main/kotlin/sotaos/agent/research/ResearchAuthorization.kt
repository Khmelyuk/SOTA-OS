package sotaos.agent.research

import sotaos.application.ports.*
import sotaos.application.services.AuthenticationService
import sotaos.application.services.AuthorityLineage
import sotaos.domain.shared.*

/** Fresh provider proof for every command; no caller-supplied Person or session is trusted. */
class ResearchAccess(
    val challenge: AuthenticationChallenge,
    val proof: CharArray,
    val authority: AuthorityId,
    val purpose: String
)

internal data class ResearchSession(
    val person: PersonId,
    val unit: SotaId,
    val providerId: String,
    val context: Context,
    val policies: Set<String>
)

class ResearchAuthorization(
    private val authentication: AuthenticationService,
    private val authorities: AuthorityRepository,
    private val clock: Clock,
    private val unit: SotaId,
    private val context: Context,
    private val rights: RightsConstraint
) {
    internal fun <T> execute(access: ResearchAccess, operation: String, resource: String,
        block: (ResearchSession, (() -> Unit) -> Unit) -> T): T {
        val session = authentication.complete(unit, access.challenge, access.proof)
        val actor = authorities.transaction {
            val policies = validate(session, access, operation, resource)
            ResearchSession(session.person, session.unit, session.providerId, context, policies)
        }
        // Network and human confirmation occur outside SQLite; revocation can commit while we wait.
        return block(actor) { commit ->
            authorities.transaction {
                validate(session, access, operation, resource)
                commit()
            }
        }
    }

    private fun validate(session: AuthenticatedSession, access: ResearchAccess,
        operation: String, resource: String): Set<String> {
        require(access.purpose.isNotBlank() && access.purpose.length <= MAX_FIELD)
        val actor = SubjectRef.Person(session.person)
        val authority = requireNotNull(authorities.findById(access.authority))
        val scope = Scope(setOf(operation), setOf(resource, unitResource(unit)))
        require(authority.subject == actor && authority.issuer != actor && authority.context == context)
        require(authority.scope.resources.containsAll(scope.resources) && operation in authority.scope.actions)
        require(AuthorityLineage(authorities).isValid(authority, scope, clock.now()))
        return rights.evaluate(RightsConstraintRequest(actor, "RESEARCH", operation, access.purpose,
            authority = authority, context = context, scope = scope)).appliedPolicies
    }
    companion object {
        /** A workspace grant must identify its Sota unit even when the same Person authenticates elsewhere. */
        fun unitResource(unit: SotaId): String = "research-unit:${unit.value}"
    }
}
