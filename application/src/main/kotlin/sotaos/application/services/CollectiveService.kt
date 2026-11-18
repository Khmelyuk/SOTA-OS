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

    fun createCore(invocation: ProtocolInvocation, name: String, founder: PersonId): Core = authorities.transaction {
        require(invocation.actor == SubjectRef.Person(founder)) { "Core founder must initiate Core creation." }
        rightsConstraint.check(invocation, "P08", "createCore", SubjectRef.Person(founder))
        val core = Core(
            id = CoreId(ids.next()),
            name = name,
            createdAt = clock.now(),
            state = LifecycleState.ACTIVE
        )
        require(cores.findById(core.id) == null) { "Core ID is already in use." }
        cores.save(core)
        // The founder explicitly joins while creating this new Core (CORE-12).
        // This bootstrap creates membership only; it grants no owner role or Authority.
        val founding = Membership(
            subject = founder,
            collective = SubjectRef.Core(core.id),
            role = null,
            start = clock.now(),
            end = null,
            state = LifecycleState.ACTIVE
        )
        memberships.save(founding)
        core
    }

    override fun invite(invocation: ProtocolInvocation, subject: PersonId, into: SubjectRef,
        role: RoleId?): Membership = authorities.transaction {
        MembershipAuthorityScope.resourceRef(into)
        require(invocation.actor == into) { "Invitation must be issued by the target collective." }
        rightsConstraint.check(invocation, "P08", "invite", into)
        val invitation = Membership(
            subject = subject,
            collective = into,
            role = role,
            start = clock.now(),
            end = null,
            state = LifecycleState.PROPOSED
        )
        require(memberships.findRecord(invitation) == null) { "Membership terms already exist." }
        memberships.save(invitation)
    }

    override fun accept(invocation: ProtocolInvocation, invitation: Membership): Membership = authorities.transaction {
        require(invocation.actor == SubjectRef.Person(invitation.subject)) {
            "Only the invited Person may accept membership."
        }
        requireCurrent(invitation, LifecycleState.PROPOSED)
        rightsConstraint.check(invocation, "P08", "accept", invitation.collective)
        val accepted = invitation.copy(state = LifecycleState.ACTIVE)
        memberships.save(accepted)
    }

    override fun leave(invocation: ProtocolInvocation, membership: Membership): Membership = authorities.transaction {
        require(invocation.actor == SubjectRef.Person(membership.subject)) {
            "Only the member may leave their membership."
        }
        requireCurrent(membership, LifecycleState.ACTIVE)
        rightsConstraint.check(invocation, "P08", "leave", membership.collective)
        val ended = membership.copy(state = LifecycleState.REVOKED, end = clock.now())
        memberships.save(ended)
    }

    override fun remove(
        invocation: ProtocolInvocation,
        membership: Membership,
        byAuthority: sotaos.domain.shared.AuthorityId
    ): Membership = authorities.transaction {
        requireCurrent(membership, LifecycleState.ACTIVE)
        val authority = authorities.findById(byAuthority)
            ?: throw MembershipNotAuthorizedException(
                "Unknown Authority ${byAuthority.value}; membership removal denied."
            )
        val requestedScope = Scope(
            actions = setOf(MembershipAuthorityScope.REMOVE_ACTION),
            resources = setOf(MembershipAuthorityScope.resourceRef(membership.collective))
        )
        val valid = AuthorityLineage(authorities).isValid(authority, requestedScope, clock.now()) &&
            authorityCheck(authority, requestedScope, clock.now())
        if (authority.subject != invocation.actor ||
            authority.accountabilityTarget != membership.collective || !valid
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
        memberships.save(removed)
    }

    private fun requireCurrent(membership: Membership, state: LifecycleState) {
        MembershipAuthorityScope.resourceRef(membership.collective)
        require(memberships.findRecord(membership) == membership) { "Membership differs from its persisted record." }
        require(membership.state == state && membership.end == null) { "Membership cannot make this transition." }
        require(!clock.now().isBefore(membership.start)) { "Clock precedes membership start." }
    }
}
