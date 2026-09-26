package sotaos.application.ports

import sotaos.domain.memory.*
import sotaos.domain.shared.*
import java.time.Instant

data class ExitTarget(val person: PersonId, val core: CoreId)
enum class ExitStage { REQUESTED, DELEGATIONS_REVOKED, RELATIONS_CLOSED, OBLIGATIONS_SETTLED, EXPORT_READY, COMPLETED }
data class ExitProcess(
    val id: String, val target: ExitTarget, val requestedAt: Instant,
    val updatedAt: Instant, val stage: ExitStage
)
data class ExitDocument(val exitId: String, val target: ExitTarget, val json: String, val sha256: String)
data class ExitReceipt(val exitId: String, val target: ExitTarget, val completedAt: Instant, val exportSha256: String)
data class ExitTransition(val exitId: String, val stage: ExitStage, val actor: PersonId, val occurredAt: Instant)

/** State, transition audit and archive share the same transaction as ExitScopeRepository. */
interface ExitRepository {
    fun <T> transaction(block: () -> T): T
    fun find(id: String): ExitProcess?
    fun findOpen(target: ExitTarget): ExitProcess?
    fun save(process: ExitProcess)
    fun appendTransition(transition: ExitTransition)
    fun transitions(id: String): List<ExitTransition>
    fun prepareExport(process: ExitProcess): ExitDocument
    fun export(id: String): ExitDocument?
}

/** Core-scoped relations only; identities, other memberships and immutable history are never deleted. */
interface ExitScopeRepository {
    fun hasMembership(target: ExitTarget): Boolean
    fun revokeDelegations(target: ExitTarget)
    fun hasDelegations(target: ExitTarget): Boolean
    fun closeRelations(target: ExitTarget)
    fun hasRelations(target: ExitTarget): Boolean
    fun retainObligations(target: ExitTarget, declaration: String, at: Instant)
    fun hasUnresolvedObligations(target: ExitTarget): Boolean
    fun terminateMembership(target: ExitTarget, at: Instant)
}

enum class ExitArtifactKind { EVENT, KNOWLEDGE, EVIDENCE }
data class ExitArtifactRef(val kind: ExitArtifactKind, val id: String)
enum class ObligationState { OPEN, RETAINED, FULFILLED }
data class ExitObligation(
    val id: String, val target: ExitTarget, val description: String,
    val state: ObligationState, val resolution: String? = null
)
data class CoreExitRelation(val id: String, val target: ExitTarget, val kind: String, val description: String)

/** Infrastructure inventory; command adapters must use ExitGovernanceService for grants and fulfillment. */
interface ExitInventoryRepository {
    fun addRelation(relation: CoreExitRelation)
    fun addObligation(obligation: ExitObligation)
    fun obligations(target: ExitTarget): List<ExitObligation>
    fun fulfillObligation(target: ExitTarget, id: String, evidence: String, at: Instant)
    fun allowExport(target: ExitTarget, ref: ExitArtifactRef)
    fun saveEvidence(target: ExitTarget, evidence: Evidence)
    fun auditGovernance(target: ExitTarget, operation: String, actor: SubjectRef,
        authority: AuthorityId, evidence: String, at: Instant)
}
