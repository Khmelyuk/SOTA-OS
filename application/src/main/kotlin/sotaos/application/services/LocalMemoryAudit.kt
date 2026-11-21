package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.memory.Event
import sotaos.domain.memory.Experience
import sotaos.domain.memory.Knowledge
import sotaos.domain.shared.*
import java.security.MessageDigest

/** Local append-only audit, sharing the enclosing MemoryService repository transaction. */
class LocalMemoryAudit(private val events: EventStore, private val clock: Clock,
    private val ids: IdGenerator) : MemoryAudit {
    override fun record(invocation: ProtocolInvocation, change: MemoryChange) {
        when (change) {
            is MemoryChange.ExperienceRecorded -> append(invocation, "LOCAL_EXPERIENCE_RECORDED",
                change.value.id.value, change.value.context,
                mapOf("before" to null, "after" to LocalMemorySnapshots.experience(change.value),
                    "source" to null, "reason" to null),
                Provenance(change.sources.first().id, invocation.actor, clock.now(),
                    change.sources.map { it.id.value }))
            is MemoryChange.KnowledgeProposed -> knowledge(invocation, "LOCAL_KNOWLEDGE_PROPOSED",
                change.value, source = change.source)
            is MemoryChange.KnowledgeValidated -> knowledge(invocation, "LOCAL_KNOWLEDGE_VALIDATED",
                change.value, previous = change.previous)
            is MemoryChange.KnowledgeDeprecated -> knowledge(invocation, "LOCAL_KNOWLEDGE_DEPRECATED",
                change.value, previous = change.previous, reason = change.reason)
        }
    }

    private fun knowledge(invocation: ProtocolInvocation, type: String, value: Knowledge,
        previous: Knowledge? = null, source: Experience? = null, reason: String? = null) {
        append(invocation, type, value.id.value, value.context,
            mapOf("before" to previous?.let(LocalMemorySnapshots::knowledge),
                "after" to LocalMemorySnapshots.knowledge(value),
                "source" to source?.let(LocalMemorySnapshots::experience), "reason" to reason),
            Provenance(null, invocation.actor, clock.now(), value.derivedFromExperience.map { it.value }))
    }

    private fun append(invocation: ProtocolInvocation, type: String, entityId: String,
        context: Context, snapshot: Map<String, Any?>, provenance: Provenance) {
        val id = EventId(ids.next())
        val payload = snapshot + mapOf("auditVersion" to 1L, "entityId" to entityId, "purpose" to invocation.purpose)
        val material = mapOf("id" to id.value, "type" to type,
            "actor" to LocalMemorySnapshots.subject(invocation.actor),
            "timestamp" to provenance.recordedAt.toString(), "context" to LocalMemorySnapshots.context(context),
            "payload" to payload, "provenance" to LocalMemorySnapshots.provenance(provenance))
        val hash = MessageDigest.getInstance("SHA-256").digest(auditValue(material).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        events.append(Event(id, type, invocation.actor, provenance.recordedAt, context, null, null,
            payload, null, provenance, hash, null))
    }
}

/** Length-prefixed values and sorted keys avoid delimiter ambiguity; this is not the P09 signature profile. */
private fun auditValue(value: Any?): String = when (value) {
    null -> "n"
    is String -> "s${value.length}:$value"
    is Long -> "i$value;"
    is List<*> -> "l${value.size}:" + value.joinToString("") { auditValue(it) }
    is Map<*, *> -> "m${value.size}:" + value.entries.sortedBy { it.key as String }
        .joinToString("") { auditValue(it.key) + auditValue(it.value) }
    else -> error("Unsupported local memory audit value")
}
