package sotaos.domain.memory

import sotaos.domain.sync.*

/** Different statements or governance statuses of one Knowledge record are retained as alternatives. */
object KnowledgeConflictRule : EntityConflictRule {
    override fun incompatible(left: StateAssertion, right: StateAssertion): Boolean =
        left.entity == right.entity && left.context == right.context &&
            left.validity.overlaps(right.validity) && left.attributes != right.attributes
}

/** Experience observations are additive facts; sync does not choose a canonical interpretation. */
object ExperienceConflictRule : EntityConflictRule {
    override fun incompatible(left: StateAssertion, right: StateAssertion): Boolean = false
}
