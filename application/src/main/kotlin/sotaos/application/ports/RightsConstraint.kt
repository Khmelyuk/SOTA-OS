package sotaos.application.ports

import sotaos.domain.relation.Authority
import sotaos.domain.shared.Context
import sotaos.domain.shared.MissionId
import sotaos.domain.shared.Scope
import sotaos.domain.shared.SubjectRef

/** Shared invocation facts needed by the constitutional check across protocols. */
data class RightsConstraintRequest(
    val actor: SubjectRef,
    /** Stable protocol identifier, e.g. P05. */
    val protocol: String,
    val operation: String,
    val purpose: String,
    val mission: MissionId? = null,
    val authority: Authority? = null,
    val context: Context? = null,
    val scope: Scope? = null,
    /** Subject or resource the command acts on, when distinct from actor. */
    val target: SubjectRef? = null
)

/** Identifiers of the policy rules that allowed a significant operation. */
data class RightsConstraintDecision(val appliedPolicies: Set<String>) {
    init {
        require(appliedPolicies.isNotEmpty()) { "At least one rights policy must be applied." }
    }
}

class RightsConstraintViolation(
    val code: String,
    message: String
) : RuntimeException(message)

/** Required application boundary: no protected command proceeds before this check succeeds. */
fun interface RightsConstraint {
    fun evaluate(request: RightsConstraintRequest): RightsConstraintDecision
}
