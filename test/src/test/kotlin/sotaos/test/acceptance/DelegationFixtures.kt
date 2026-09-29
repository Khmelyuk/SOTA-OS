package sotaos.test.acceptance

import sotaos.application.ports.*
import sotaos.application.services.AuthorityService
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.util.UUID

internal val delegateB = SubjectRef.Person(PersonId("delegate-b"))
internal val delegateC = SubjectRef.Person(PersonId("delegate-c"))
internal val delegateD = SubjectRef.Person(PersonId("delegate-d"))

internal fun ExitFixture.authorityService() = AuthorityService(repos.authorities, Clock { now },
    IdGenerator { UUID.randomUUID().toString() }, RightsConstraintDecorator(listOf(NoAgentActionRule)))

internal fun ExitFixture.delegate(parent: Authority, subject: SubjectRef,
    scope: Scope = parent.scope, validity: Validity = parent.validity, context: Context = parent.context,
    accountable: SubjectRef = parent.accountabilityTarget, parentId: AuthorityId? = parent.id): Authority =
    authorityService().grant(ProtocolInvocation(parent.subject, "Explicit redelegation", context),
        parent.subject, subject, scope, context, AuthorityBasis(note = "delegation test"),
        validity, accountable, parentId)

internal fun ExitFixture.revocations() = store.database.authorityLineageQueries
    .selectAuthorityRevocations().executeAsList()
