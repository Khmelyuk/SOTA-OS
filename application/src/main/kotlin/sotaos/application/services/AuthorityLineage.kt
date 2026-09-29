package sotaos.application.services

import sotaos.application.ports.AuthorityRepository
import sotaos.domain.relation.Authority
import sotaos.domain.relation.coversDelegation
import sotaos.domain.rights.covers
import sotaos.domain.shared.*
import java.time.Instant

/** Iterative, fail-closed validation: a live leaf cannot outlive or widen any ancestor. */
class AuthorityLineage(private val authorities: AuthorityRepository) {
    fun isValid(authority: Authority, scope: Scope, at: Instant): Boolean {
        val stored = authorities.findById(authority.id)
        var current = stored
        var valid = stored != null && stored == authority && stored.scope.covers(scope)
        val visited = mutableSetOf<AuthorityId>()
        while (valid && current != null) {
            val node = current
            valid = visited.add(node.id) && node.state == LifecycleState.ACTIVE && node.validity.isActiveAt(at)
            val parentId = node.parentAuthorityId
            if (valid && parentId != null) {
                val parent = authorities.findById(parentId)
                valid = parent != null && parent.coversDelegation(node)
                current = parent
            } else {
                current = null
            }
        }
        return valid
    }
}
