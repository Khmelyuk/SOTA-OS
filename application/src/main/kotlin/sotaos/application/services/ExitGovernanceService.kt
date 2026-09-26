package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.shared.*

/** Explicit local authority may release artifacts or confirm fulfillment, never force another Person to exit. */
class ExitGovernanceService(
    private val exits: ExitRepository,
    private val inventory: ExitInventoryRepository,
    private val authorities: AuthorityRepository,
    private val clock: Clock,
    private val rights: RightsConstraint
) {
    fun allowExport(invocation: ProtocolInvocation, target: ExitTarget, ref: ExitArtifactRef,
        authorityId: AuthorityId) = exits.transaction {
        authorize(invocation, target, authorityId, "exit.export", "${ref.kind}:${ref.id}")
        inventory.allowExport(target, ref)
        inventory.auditGovernance(target, "exit.export", invocation.actor, authorityId,
            "${ref.kind}:${ref.id}", clock.now())
    }

    fun fulfillObligation(invocation: ProtocolInvocation, target: ExitTarget, obligationId: String,
        evidence: String, authorityId: AuthorityId) = exits.transaction {
        require(evidence.isNotBlank()) { "Fulfillment requires an evidence reference." }
        authorize(invocation, target, authorityId, "exit.settle", "obligation:$obligationId")
        inventory.fulfillObligation(target, obligationId, evidence, clock.now())
        inventory.auditGovernance(target, "exit.settle", invocation.actor, authorityId, evidence, clock.now())
    }

    private fun authorize(invocation: ProtocolInvocation, target: ExitTarget, id: AuthorityId,
        operation: String, resource: String) {
        require(invocation.actor is SubjectRef.Person && invocation.actor != SubjectRef.Person(target.person))
        val authority = requireNotNull(authorities.findById(id))
        require(authority.subject == invocation.actor && authority.issuer != invocation.actor)
        require(authority.accountabilityTarget == SubjectRef.Core(target.core))
        require(authority.state == LifecycleState.ACTIVE && authority.validity.isActiveAt(clock.now()))
        require(invocation.context == authority.context && operation in authority.scope.actions)
        val requested = Scope(setOf(operation), setOf("core:${target.core.value}", resource))
        require(authority.scope.resources.containsAll(requested.resources))
        rights.check(invocation, "P10", operation, SubjectRef.Person(target.person), authority, requested)
    }
}
