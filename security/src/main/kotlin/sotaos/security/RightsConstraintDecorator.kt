package sotaos.security

import sotaos.application.ports.RightsConstraint
import sotaos.application.ports.RightsConstraintDecision
import sotaos.application.ports.RightsConstraintRequest
import sotaos.application.ports.RightsConstraintViolation

/** One rule may deny an operation; a permission or Authority cannot override it. */
interface RightsConstraintRule {
    val policyId: String
    fun violation(request: RightsConstraintRequest): RightsConstraintViolation?
}

/**
 * Composes mandatory constitutional rules at the application boundary.
 * An empty rule set is rejected so the security layer cannot silently
 * degrade into an allow-all implementation.
 */
class RightsConstraintDecorator(
    private val rules: List<RightsConstraintRule>
) : RightsConstraint {
    init {
        require(rules.isNotEmpty()) { "Rights constraint policy must contain at least one rule." }
        require(rules.map { it.policyId }.distinct().size == rules.size) {
            "Rights constraint policy identifiers must be unique."
        }
    }

    override fun evaluate(request: RightsConstraintRequest): RightsConstraintDecision {
        rules.forEach { rule ->
            rule.violation(request)?.let { throw it }
        }
        return RightsConstraintDecision(rules.mapTo(linkedSetOf()) { it.policyId })
    }
}

/** ADR-006: Agents have no execution runtime or principal authority in the MVP. */
object NoAgentActionRule : RightsConstraintRule {
    override val policyId: String = "ADR-006-no-agent-action-v1"

    override fun violation(request: RightsConstraintRequest): RightsConstraintViolation? =
        if (request.actor is sotaos.domain.shared.SubjectRef.Agent) {
            RightsConstraintViolation(
                code = "AGENT_ACTION_NOT_IN_MVP",
                message = "Agent cannot execute '${request.operation}' in the MVP."
            )
        } else {
            null
        }
}
