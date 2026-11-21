package sotaos.api.memory

import sotaos.application.ports.MemoryAudit
import sotaos.application.ports.MemoryChange
import sotaos.domain.shared.ProtocolInvocation
import sotaos.protocol.JsonMemorySnapshotCodec

/** Pre-write source verification and the existing signed fact, inside the shared entity transaction. */
internal class SignedMemoryAudit(private val sources: SignedMemorySources,
    private val journal: SignedMemoryJournal) : MemoryAudit {
    private val snapshots = JsonMemorySnapshotCodec()

    override fun record(invocation: ProtocolInvocation, change: MemoryChange) {
        when (change) {
            is MemoryChange.ExperienceRecorded -> journal.append(invocation,
                MemoryFact("EXPERIENCE_RECORDED", change.value.id.value, snapshots.encode(change.value)),
                sources.events(change.sources))
            is MemoryChange.KnowledgeProposed -> journal.append(invocation,
                MemoryFact("KNOWLEDGE_PROPOSED", change.value.id.value, snapshots.encode(change.value)),
                listOf(sources.experience(change.source)))
            is MemoryChange.KnowledgeValidated -> journal.append(invocation,
                MemoryFact("KNOWLEDGE_VALIDATED", change.value.id.value, snapshots.encode(change.value)),
                listOf(sources.knowledge(change.previous)))
            is MemoryChange.KnowledgeDeprecated -> journal.append(invocation,
                MemoryFact("KNOWLEDGE_DEPRECATED", change.value.id.value, snapshots.encode(change.value)),
                listOf(sources.knowledge(change.previous)), change.reason)
        }
    }
}
