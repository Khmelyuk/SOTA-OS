package sotaos.domain.relation

import sotaos.domain.sync.*

/**
 * ADR-004: concurrent assertions for the same Authority, context and overlapping window
 * conflict when any declared term differs (including scope, issuer or lifecycle status).
 * Producers must include all terms, rather than publish partial field patches.
 */
object AuthorityConflictRule : EntityConflictRule {
    override fun incompatible(left: StateAssertion, right: StateAssertion): Boolean =
        left.entity == right.entity && left.context == right.context &&
            left.validity.overlaps(right.validity) && left.attributes != right.attributes
}
