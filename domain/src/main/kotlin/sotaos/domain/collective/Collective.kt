package sotaos.domain.collective

import sotaos.domain.shared.*
import java.time.Instant

/**
 * CORE: small autonomous group. Reference Architecture §13: CORE, SOTA,
 * NETWORK are distinct subjects, never collapsed into one object with
 * different access levels.
 */
data class Core(
    val id: CoreId,
    val name: String,
    val createdAt: Instant,
    val state: LifecycleState
)

/**
 * SOTA: autonomous collective unit. May wrap one or more Cores.
 * Invariant: CORE ∈ SOTA does NOT imply SOTA owns CORE (P-I11
 * Non-Ownership, Architecture Core §40).
 */
data class Sota(
    val id: SotaId,
    val name: String,
    val cores: List<CoreId>,
    val createdAt: Instant,
    val state: LifecycleState
)

/**
 * MEMBERSHIP: participation relation. Deliberately separate from ROLE
 * and AUTHORITY (Data Model §23: "дозволяє не плутати членство з роллю
 * і повноваженням").
 */
data class Membership(
    val subject: PersonId,
    val collective: SubjectRef, // Core or Sota
    val role: RoleId?,
    val start: Instant,
    val end: Instant?,
    val state: LifecycleState
)

@JvmInline
value class RoleId(val value: String)

/**
 * ROLE describes a functional position. ROLE != AUTHORITY, ROLE !=
 * COMPETENCE, ROLE != STATUS (Data Model §26). A Role MAY be referenced
 * by an Authority grant as context, but never substitutes for one.
 */
data class Role(
    val id: RoleId,
    val name: String,
    val collective: SubjectRef,
    val description: String = ""
)

/**
 * P08 (membership-level subset) — voluntary association, not automatic
 * membership (Architecture Core CORE-12).
 */
object MembershipAuthorityScope {
    const val REMOVE_ACTION = "membership.remove"

    fun resourceRef(collective: SubjectRef): String = when (collective) {
        is SubjectRef.Core -> "core:${collective.id.value}"
        is SubjectRef.Sota -> "sota:${collective.id.value}"
        else -> throw IllegalArgumentException("Membership collective must be a Core or Sota.")
    }
}

interface MembershipProtocol {
    fun invite(invocation: ProtocolInvocation, subject: PersonId, into: SubjectRef, role: RoleId?): Membership
    fun accept(invocation: ProtocolInvocation, invitation: Membership): Membership
    fun leave(invocation: ProtocolInvocation, membership: Membership): Membership
    fun remove(invocation: ProtocolInvocation, membership: Membership, byAuthority: AuthorityId): Membership
}
