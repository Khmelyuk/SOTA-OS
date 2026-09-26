package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.shared.*

/** Invocation must come from an authenticated local adapter, never from a P09 sender field. */
class ProvisioningAuthorization(
    private val authorities: AuthorityRepository,
    private val clock: Clock,
    private val context: Context,
    private val rights: RightsConstraint
) {
    fun authorize(invocation: ProtocolInvocation, authorityId: AuthorityId,
        operation: String, target: String, subjects: Set<SubjectRef>): ProvisioningAudit {
        require(invocation.actor !is SubjectRef.Agent && invocation.actor !in subjects) {
            "Self-provisioning and Agent provisioning are forbidden."
        }
        require(invocation.context == context) { "Wrong provisioning context." }
        val now = clock.now()
        val authority = requireNotNull(authorities.findById(authorityId)) { "Unknown provisioning authority." }
        require(authority.subject == invocation.actor && authority.issuer != invocation.actor) {
            "Provisioning requires independently delegated, actor-bound authority."
        }
        require(authority.state == LifecycleState.ACTIVE && authority.validity.isActiveAt(now))
        require(authority.context == context && operation in authority.scope.actions)
        require(target in authority.scope.resources) { "An explicit provisioning target is required." }
        rights.check(invocation, "P09", operation, authority = authority,
            scope = Scope(setOf(operation), setOf(target)), context = context)
        return ProvisioningAudit(target, operation, invocation.actor, authorityId, invocation.purpose, now)
    }
}
