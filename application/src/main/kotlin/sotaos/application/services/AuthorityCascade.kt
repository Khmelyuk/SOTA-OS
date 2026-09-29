package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.relation.Authority
import sotaos.domain.shared.*
import java.time.Instant

val terminalAuthorityStates = setOf(LifecycleState.REVOKED, LifecycleState.COMPLETED,
    LifecycleState.REJECTED, LifecycleState.ABORTED, LifecycleState.FAILED)

/** Local indexed traversal never depends on network access or recursive stack depth. */
class AuthorityCascade(private val authorities: AuthorityRepository) {
    fun family(root: Authority): List<Authority> {
        val result = mutableListOf<Authority>()
        val pending = ArrayDeque<Authority>()
        val visited = mutableSetOf<AuthorityId>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current.id)) continue
            require(current.accountabilityTarget == root.accountabilityTarget) { "Cross-collective lineage denied." }
            result.add(current)
            pending.addAll(authorities.findChildren(current.id))
        }
        return result
    }

    fun revoke(roots: List<Authority>, actor: SubjectRef, reason: String, at: Instant) = authorities.transaction {
        require(reason.isNotBlank())
        val visited = mutableSetOf<AuthorityId>()
        roots.forEach { root ->
            family(root).forEach { authority ->
                if (visited.add(authority.id) && authority.state !in terminalAuthorityStates) {
                    authorities.save(authority.copy(state = LifecycleState.REVOKED))
                    authorities.appendRevocation(AuthorityRevocation(authority.id, root.id, actor, reason, at))
                }
            }
        }
    }
}
