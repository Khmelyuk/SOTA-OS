package sotaos.sync

import sotaos.domain.memory.*
import sotaos.domain.relation.AuthorityConflictRule
import sotaos.domain.shared.EventId
import sotaos.domain.sync.*

/** No timestamp tie-breakers: causal predecessors are history; concurrent alternatives survive. */
class DeterministicMerge {
    private val rules = mapOf(
        SyncEntityKind.AUTHORITY to AuthorityConflictRule,
        SyncEntityKind.KNOWLEDGE to KnowledgeConflictRule,
        SyncEntityKind.EXPERIENCE to ExperienceConflictRule
    )

    fun ordered(records: Collection<SyncRecord>): List<SyncRecord> {
        val remaining = records.associateBy { it.event.id }.toMutableMap()
        require(remaining.size == records.size) { "Duplicate event identities in merge input." }
        val result = mutableListOf<SyncRecord>()
        val seen = mutableSetOf<EventId>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { seen.containsAll(it.parents) }.sortedBy { it.event.id.value }
            require(ready.isNotEmpty()) { "Missing causal parent or cyclic event history." }
            ready.forEach { result.add(it); seen.add(it.event.id); remaining.remove(it.event.id) }
        }
        return result
    }

    fun conflicts(records: Collection<SyncRecord>): List<SyncConflict> {
        val sorted = ordered(records)
        val ancestors = ancestors(sorted)
        val conflicts = mutableListOf<SyncConflict>()
        sorted.forEachIndexed { index, left ->
            sorted.drop(index + 1).filter { concurrent(left, it, ancestors) }.forEach { right ->
                val a = left.assertion
                val b = right.assertion
                if (a != null && b != null && rules.getValue(a.entity.kind).incompatible(a, b)) {
                    val ids = listOf(left.event.id, right.event.id).sortedBy { it.value }
                    conflicts.add(SyncConflict(a.entity, ids[0], ids[1]))
                }
            }
        }
        return conflicts.sortedWith(compareBy({ it.left.value }, { it.right.value }))
    }

    fun project(entity: SyncEntity, records: List<SyncRecord>, conflicts: List<SyncConflict>): SyncProjection {
        val sorted = ordered(records)
        val ancestry = ancestors(sorted)
        val matching = sorted.filter { it.assertion?.entity == entity }
        // Keep assertions for distinct contexts/windows; only a causal replacement of the same window supersedes.
        val heads = matching.filter { candidate ->
            matching.none { later ->
                candidate.event.id in ancestry.getValue(later.event.id) &&
                    candidate.assertion?.context == later.assertion?.context &&
                    candidate.assertion?.validity == later.assertion?.validity
            }
        }
        return SyncProjection(entity, heads.sortedBy { it.event.id.value }, conflicts.filter { it.entity == entity })
    }

    private fun ancestors(ordered: List<SyncRecord>): Map<EventId, Set<EventId>> {
        val result = mutableMapOf<EventId, Set<EventId>>()
        ordered.forEach { record ->
            result[record.event.id] = record.parents + record.parents.flatMap { result.getValue(it) }
        }
        return result
    }

    private fun concurrent(left: SyncRecord, right: SyncRecord, ancestors: Map<EventId, Set<EventId>>): Boolean =
        left.origin != right.origin && left.event.id !in ancestors.getValue(right.event.id) &&
            right.event.id !in ancestors.getValue(left.event.id)
}
