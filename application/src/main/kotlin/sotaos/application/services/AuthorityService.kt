package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.relation.*
import sotaos.domain.rights.covers
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
        accountableTo: SubjectRef
    ): Authority {
        require(invocation.actor == issuer) { "Authority grant must be initiated by its issuer." }
        // Delegation ceiling check (see class doc): a Core/Sota issuer
        // grants as an originating principal; a Person/Agent issuer is
        // always re-delegating and must already hold covering authority.
        val issuerIsOriginatingPrincipal = issuer is SubjectRef.Core || issuer is SubjectRef.Sota
        if (!issuerIsOriginatingPrincipal) {
            val issuerHolds = authorities.findActiveFor(issuer, context)
            val covering = issuerHolds.any { it.scope.covers(scope) }
            if (!covering) {
                throw AuthorityException(
                    "Delegated Authority ceiling violated: issuer ${issuer} does not hold " +
                        "authority covering requested scope ${scope.actions} in context $context"
                )
            }
        }

        rightsConstraint.check(invocation, "P04", "grant", subject, scope = scope, context = context)
        val authority = Authority(
            id = AuthorityId(ids.next()),
            issuer = issuer,
            subject = subject,
            scope = scope,
            context = context,
            basis = basis,
            validity = validity,
            accountabilityTarget = accountableTo,
            state = LifecycleState.ACTIVE
        )
        return authorities.save(authority)
    }

    override fun revoke(invocation: ProtocolInvocation, authority: Authority, reason: String): Authority {
        require(invocation.actor == authority.issuer) { "Only the issuer may revoke this authority." }
        rightsConstraint.check(invocation, "P04", "revoke", authority.subject, authority = authority)
        // Must be O(1) local — no network/sync dependency in this call
        // path (Security Architecture §23: revocation faster than grant).
        val revoked = authority.copy(state = LifecycleState.REVOKED)
        return authorities.save(revoked)
    }

    override fun isValid(authority: Authority, forScope: Scope, at: Instant): Boolean {
        return authority.state == LifecycleState.ACTIVE &&
            authority.validity.isActiveAt(at) &&
            authority.scope.covers(forScope)
    }
}
