package sotaos.test.acceptance

import sotaos.application.ports.*
import sotaos.application.services.CollectiveService
import sotaos.domain.collective.Membership
import sotaos.domain.collective.RoleId
import sotaos.domain.shared.*
import sotaos.test.fakes.TestRightsConstraint
import java.util.UUID

internal val membershipCore = SubjectRef.Core(CoreId("core"))

internal fun SignedActionFixture.collective(
    rights: RightsConstraint = TestRightsConstraint,
    clock: Clock = Clock { now },
    ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
) = CollectiveService(repos.cores, repos.memberships, repos.authorities,
    { _, _, _ -> true }, clock, ids, rights)

internal fun SignedActionFixture.invitation(role: RoleId? = null): Membership = collective().invite(
    invocation.copy(actor = membershipCore), actor.id, membershipCore, role)

internal fun SignedActionFixture.removalAuthority() = repos.authorities.save(
    requireNotNull(repos.authorities.findById(authorityId)).copy(
        scope = Scope(setOf("membership.remove"), setOf("core:core"))))
