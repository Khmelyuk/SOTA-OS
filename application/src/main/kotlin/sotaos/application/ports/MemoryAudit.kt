package sotaos.application.ports

import sotaos.domain.memory.Event
import sotaos.domain.memory.Experience
import sotaos.domain.memory.Knowledge
import sotaos.domain.shared.ProtocolInvocation

sealed interface MemoryChange {
    data class ExperienceRecorded(val value: Experience, val sources: List<Event>) : MemoryChange
    data class KnowledgeProposed(val value: Knowledge, val source: Experience) : MemoryChange
    data class KnowledgeValidated(val value: Knowledge, val previous: Knowledge) : MemoryChange
    data class KnowledgeDeprecated(val value: Knowledge, val previous: Knowledge, val reason: String) : MemoryChange
}

/** Called before the entity write, inside its transaction. Failure must roll back both audit and entity. */
fun interface MemoryAudit {
    fun record(invocation: ProtocolInvocation, change: MemoryChange)
}
