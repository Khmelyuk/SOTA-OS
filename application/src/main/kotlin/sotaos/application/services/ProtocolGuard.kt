package sotaos.application.services

import sotaos.application.ports.RightsConstraint
import sotaos.application.ports.RightsConstraintDecision
import sotaos.application.ports.RightsConstraintRequest
import sotaos.domain.shared.ProtocolInvocation
import sotaos.domain.shared.SubjectRef
import sotaos.domain.relation.Authority
import sotaos.domain.shared.Scope

internal fun RightsConstraint.check(
    invocation: ProtocolInvocation,
    protocol: String,
    operation: String,
    target: SubjectRef? = null,
    authority: Authority? = null,
    scope: Scope? = null,
    context: sotaos.domain.shared.Context? = null
): RightsConstraintDecision = evaluate(
    RightsConstraintRequest(
        actor = invocation.actor,
        protocol = protocol,
        operation = operation,
        purpose = invocation.purpose,
        authority = authority,
        context = authority?.context ?: context ?: invocation.context,
        scope = scope,
        target = target
    )
)
