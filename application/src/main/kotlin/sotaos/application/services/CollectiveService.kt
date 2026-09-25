package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.collective.*
import sotaos.domain.shared.*

/**
 * AC-02: create CORE without a central administrator. Note there is no
 * "adminApproval" parameter anywhere in this class — Core creation
 * requires only a founding Person, per Architecture Core CORE-09
 * Self-Organization.
 */
class MembershipNotAuthorizedException(message: String) : RuntimeException(message)

class CollectiveService(
    private val cores: CoreRepository,
    private val memberships: MembershipRepository,
    private val authorities: AuthorityRepository,
    private val authorityCheck: (sotaos.domain.relation.Authority, Scope, java.time.Instant) -> Boolean,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val rightsConstraint: RightsConstraint
) : MembershipProtocol {

    fun createCore(invocation: ProtocolInvocation, name: String, founder: PersonId): Core {
        require(invocation.actor == SubjectRef.Person(founder)) { "Core founder must initiate Core creation." }
        rightsConstraint.check(invocation, "P08", "createCore", SubjectRef.Person(founder))
        val core = Core(
            id = CoreId(ids.next()),
            name = name,
            createdAt = clock.now(),
            state = LifecycleState.ACTIVE
        )
        cores.save(core)
        // Founder becomes a member via the same voluntary-association
        // path as any other invitee (CORE-12 Voluntary Association) —
        // no privileged "owner" bypass.
        val founding = Membership(
            subject = founder,
            collective = SubjectRef.Core(core.id),
            role = null,
            start = clock.now(),
            end = null,
            state = LifecycleState.ACTIVE
        )
        memberships.save(founding)
        return core
    }

    override fun invite(invocation: ProtocolInvocation, subject: PersonId, into: SubjectRef,
        role: RoleId?): Membership {
        MembershipAuthorityScope.resourceRef(into)
        rightsConstraint.check(invocation, "P08", "invite", into)
        return Membership(
            subject = subject,
            collective = into,
            role = role,
            start = clock.now(),
            end = null,
            state = LifecycleState.PROPOSED
        )
    }

    override fun accept(invocation: ProtocolInvocation, invitation: Membership): Membership {
        require(invocation.actor == SubjectRef.Person(invitation.subject)) {
            "Only the invited Person may accept membership."
        }
        MembershipAuthorityScope.resourceRef(invitation.collective)
        rightsConstraint.check(invocation, "P08", "accept", invitation.collective)
        val accepted = invitation.copy(state = LifecycleState.ACTIVE)
        return memberships.save(accepted)
    }

    override fun leave(invocation: ProtocolInvocation, membership: Membership): Membership {
        require(invocation.actor == SubjectRef.Person(membership.subject)) {
            "Only the member may leave their membership."
        }
        MembershipAuthorityScope.resourceRef(membership.collective)
        rightsConstraint.check(invocation, "P08", "leave", membership.collective)
        val ended = membership.copy(state = LifecycleState.REVOKED, end = clock.now())
        return memberships.save(ended)
    }

    override fun remove(
        invocation: ProtocolInvocation,
        membership: Membership,
        byAuthority: sotaos.domain.shared.AuthorityId
    ): Membership {
        val authority = authorities.findById(byAuthority)
            ?: throw MembershipNotAuthorizedException(
                "Unknown Authority ${byAuthority.value}; membership removal denied."
            )
        val requestedScope = Scope(
            actions = setOf(MembershipAuthorityScope.REMOVE_ACTION),
            resources = setOf(MembershipAuthorityScope.resourceRef(membership.collective))
        )
        if (authority.subject != invocation.actor ||
            authority.accountabilityTarget != membership.collective ||
            !authorityCheck(authority, requestedScope, clock.now())
        ) {
            throw MembershipNotAuthorizedException(
                "Authority ${byAuthority.value} is not active and scoped for this actor and collective."
            )
        }
        rightsConstraint.check(
            invocation, "P08", "remove", membership.collective,
            authority = authority, scope = requestedScope
        )
        val removed = membership.copy(state = LifecycleState.REVOKED, end = clock.now())
        return memberships.save(removed)
    }
}
