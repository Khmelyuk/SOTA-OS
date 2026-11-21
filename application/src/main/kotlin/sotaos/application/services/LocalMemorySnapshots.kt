package sotaos.application.services

import sotaos.domain.memory.Experience
import sotaos.domain.memory.Knowledge
import sotaos.domain.shared.*

/** Version-1 local audit maps; distinct from the canonical signed P07 JSON wire format. */
internal object LocalMemorySnapshots {
    fun experience(value: Experience): Map<String, Any?> = mapOf(
        "id" to value.id.value, "events" to value.derivedFromEvents.map { it.value },
        "context" to context(value.context), "whatWasTried" to value.whatWasTried, "outcome" to value.outcome,
        "conclusion" to value.conclusion, "applicability" to value.applicabilityConditions,
        "confidence" to value.confidence.name, "provenance" to provenance(value.provenance)
    )

    fun knowledge(value: Knowledge): Map<String, Any?> = mapOf(
        "id" to value.id.value, "statement" to value.statement,
        "experiences" to value.derivedFromExperience.map { it.value }, "evidence" to value.evidence.map { it.value },
        "context" to context(value.context), "status" to value.status.name,
        "knowledgeVersion" to value.version.toLong(), "supersedes" to value.supersedes?.value,
        "provenance" to provenance(value.provenance)
    )

    fun context(value: Context): Map<String, Any?> = mapOf(
        "domain" to value.domain, "mission" to value.mission?.value, "description" to value.description)

    fun provenance(value: Provenance): Map<String, Any?> = mapOf(
        "sourceEventId" to value.sourceEventId?.value, "author" to subject(value.author),
        "recordedAt" to value.recordedAt.toString(), "derivedFrom" to value.derivedFrom)

    fun subject(value: SubjectRef): Map<String, String> = when (value) {
        is SubjectRef.Person -> mapOf("kind" to "PERSON", "id" to value.id.value)
        is SubjectRef.Core -> mapOf("kind" to "CORE", "id" to value.id.value)
        is SubjectRef.Sota -> mapOf("kind" to "SOTA", "id" to value.id.value)
        is SubjectRef.Agent -> mapOf("kind" to "AGENT", "id" to value.id.value)
    }
}
