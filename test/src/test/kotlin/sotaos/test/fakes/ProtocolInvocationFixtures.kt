package sotaos.test.fakes

import sotaos.application.ports.RightsConstraint
import sotaos.domain.shared.ProtocolInvocation
import sotaos.domain.shared.SubjectRef
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator

val TestRightsConstraint: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
fun testInvocation(actor: SubjectRef) = ProtocolInvocation(actor, "test fixture command")
