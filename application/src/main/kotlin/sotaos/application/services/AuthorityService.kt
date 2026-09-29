package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.relation.*
import sotaos.domain.shared.*
import java.time.Instant

class AuthorityException(message: String) : RuntimeException(message)

/**
 * Implements P02 Trust, P03 Competence, P04 Authority/Delegation for
 * the vertical slice (AC-05, AC-06).
 *
 * Enforces: Delegated Authority(subject) <= Authority(issuer), scoped
 * (Agent/AI Architecture §21, reused generally per Security
 * Architecture §29 Trust Delegation).
 *
 * MVP rule for "who counts as an originating (root) issuer":
 * a COLLECTIVE (SubjectRef.Core / SubjectRef.Sota) granting authority
 * to one of its own members is treated as an originating grant — the
 * Core/Sota's standing to delegate to its own membership follows
 * directly from CORE-07/CORE-11 and does not require a pre-existing
 * Authority row (there would be none: it is the first grant for that
 * mission). A PERSON or AGENT acting as issuer, by contrast, is always
 * re-delegating and MUST hold an existing ACTIVE Authority of
 * equal-or-wider scope in the same context — this is what prevents
 * uncontrolled escalation (Agent/AI Architecture §21, §22 No
 * Self-Escalation). This rule is recorded explicitly here so it is
 * falsifiable by a later ADR, not silently assumed.
 */
class AuthorityService(
    private val authorities: AuthorityRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val rightsConstraint: RightsConstraint
) : AuthorityProtocol {

    override fun requestTrust(invocation: ProtocolInvocation, subject: SubjectRef, target: SubjectRef,
        context: Context, scope: Scope): Trust {
        require(invocation.actor == subject) { "Trust request must be initiated by its subject." }
        rightsConstraint.check(invocation, "P02", "requestTrust", target, scope = scope, context = context)
        return Trust(
            subject = subject,
            target = target,
            context = context,
            scope = scope,
            evidence = emptyList(),
            validity = Validity(from = clock.now(), until = null),
            state = LifecycleState.PROPOSED
        )
    }

    override fun presentCompetence(invocation: ProtocolInvocation, subject: SubjectRef, domain: String,
        evidence: List<EvidenceId>): Competence {
        require(invocation.actor == subject) { "Competence must be presented by its subject." }
        rightsConstraint.check(invocation, "P03", "presentCompetence", subject)
        return Competence(
            subject = subject,
            domain = domain,
            scope = Scope(actions = emptySet()),
            level = if (evidence.isEmpty()) CompetenceLevel.DECLARED else CompetenceLevel.DEMONSTRATED,
            evidence = evidence,
            validity = Validity(from = clock.now(), until = null)
        )
    }

    override fun grant(
        invocation: ProtocolInvocation,
        issuer: SubjectRef,
        subject: SubjectRef,
        scope: Scope,
        context: Context,
        basis: AuthorityBasis,
        validity: Validity,
        accountableTo: SubjectRef,
        parentAuthorityId: AuthorityId?
    ): Authority = authorities.transaction {
        require(invocation.actor == issuer) { "Authority grant must be initiated by its issuer." }
        require(issuer != subject) { "Self-delegation is forbidden." }
        validity.until?.let { require(it.isAfter(validity.from)) { "Invalid validity window." } }
        val requested = Authority(AuthorityId(ids.next()), issuer, subject, scope, context, basis,
            validity, accountableTo, LifecycleState.ACTIVE, parentAuthorityId)
        require(authorities.findById(requested.id) == null) { "Authority ID is already in use." }
        val parent = selectParent(requested)

        rightsConstraint.check(invocation, "P04", "grant", subject, scope = scope, context = context)
        authorities.save(requested.copy(parentAuthorityId = parent?.id))
    }

    private fun selectParent(requested: Authority): Authority? {
        val explicit = requested.parentAuthorityId
        if (explicit == null && (requested.issuer is SubjectRef.Core || requested.issuer is SubjectRef.Sota)) {
            require(requested.accountabilityTarget == requested.issuer) { "Root must be accountable to its issuer." }
            return null
        }
        val candidates = if (explicit == null) authorities.findActiveFor(requested.issuer, requested.context)
            else listOfNotNull(authorities.findById(explicit))
        val covering = candidates.filter { parent ->
            parent.coversDelegation(requested) &&
                AuthorityLineage(authorities).isValid(parent, requested.scope, clock.now())
        }
        if (covering.size != 1) throw AuthorityException(
            "Delegation requires one valid covering parent; specify parentAuthorityId when ambiguous."
        )
        return covering.single()
    }

    override fun revoke(invocation: ProtocolInvocation, authority: Authority, reason: String): Authority =
        authorities.transaction {
            val stored = requireNotNull(authorities.findById(authority.id)) { "Unknown authority." }
            require(invocation.actor == stored.issuer) { "Only the stored issuer may revoke this authority." }
            rightsConstraint.check(invocation, "P04", "revoke", stored.subject, authority = stored)
            AuthorityCascade(authorities).revoke(listOf(stored), invocation.actor, reason, clock.now())
            requireNotNull(authorities.findById(stored.id))
        }

    override fun isValid(authority: Authority, forScope: Scope, at: Instant): Boolean =
        AuthorityLineage(authorities).isValid(authority, forScope, at)
}
