package sotaos.domain.sync

import sotaos.domain.memory.Event
import sotaos.domain.shared.*

/** Entity identity is separate from the event identity and from its transport origin. */
data class SyncEntity(val kind: SyncEntityKind, val id: String) {
    init { require(id.isNotBlank()) }
}

enum class SyncEntityKind { AUTHORITY, KNOWLEDGE, EXPERIENCE }

/**
 * A normalized assertion about derived state, not permission to execute a remote command.
 * Attribute names and values are supplied by the entity's producer; omission is significant.
 * The validity interval is half-open [from, until); null until means unbounded.
 */
data class StateAssertion(
    val entity: SyncEntity,
    val context: Context,
    val validity: Validity,
    val attributes: Map<String, String>
) {
    init {
        require(attributes.isNotEmpty() && attributes.keys.none(String::isBlank))
        require(validity.until == null || validity.until.isAfter(validity.from))
    }
}

/** Origin and parents are immutable across forwarding; a receiving peer is not the origin. */
data class SyncRecord(
    val event: Event,
    val origin: SotaId,
    val parents: Set<EventId> = emptySet(),
    val assertion: StateAssertion? = null,
    val version: Int = 1
) {
    init {
        require(event.id.value.isNotBlank() && origin.value.isNotBlank())
        require(version == 1) { "Unsupported sync record version." }
        require(event.id !in parents) { "An event cannot cause itself." }
        require(event.correctsEventId == null || event.correctsEventId in parents) {
            "Corrections must explicitly reference their cause."
        }
    }
}

/** The retained records carry both provenance and authority references, without flattening either. */
data class SyncConflict(val entity: SyncEntity, val left: EventId, val right: EventId) {
    init { require(left.value < right.value) { "Conflict pairs must use canonical event-ID order." } }
}

data class SyncProjection(
    val entity: SyncEntity,
    val assertions: List<SyncRecord>,
    val conflicts: List<SyncConflict>
) {
    val state: LifecycleState
        get() = if (conflicts.isEmpty()) LifecycleState.RECORDED else LifecycleState.CONTESTED
}

fun interface EntityConflictRule {
    fun incompatible(left: StateAssertion, right: StateAssertion): Boolean
}

fun Validity.overlaps(other: Validity): Boolean =
    (until == null || other.from.isBefore(until)) &&
        (other.until == null || from.isBefore(other.until))
