package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase

/** Approval freezes an owned, Core-scoped snapshot; authorship alone never grants export. */
internal class ExitArtifactMapping(private val db: SotaOsDatabase) {
    fun approved(target: ExitTarget, ref: ExitArtifactRef): String = when (ref.kind) {
        ExitArtifactKind.EVENT -> event(target, ref.id)
        ExitArtifactKind.KNOWLEDGE -> knowledge(target, ref.id)
        ExitArtifactKind.EVIDENCE -> evidence(target, ref.id)
    }.toString()

    private fun event(target: ExitTarget, id: String): JsonObject {
        val row = requireNotNull(db.schemaQueries.selectEventById(id).executeAsOneOrNull())
        require(row.actor_kind == "PERSON" && row.actor_id == target.person.value)
        val authority = row.authority_ref?.let { db.schemaQueries.selectAuthorityById(it).executeAsOneOrNull() }
        require(authority?.accountable_kind == "CORE" && authority.accountable_id == target.core.value) {
            "Export requires explicit Core provenance."
        }
        return buildJsonObject {
            put("id", row.event_id)
            put("type", row.type)
            put("actor", row.actor_id)
            put("timestamp", row.timestamp)
            put("authorityRef", row.authority_ref)
            put("decisionRef", row.decision_ref)
            put("context", Json.parseToJsonElement(row.context_json))
            put("payload", Json.parseToJsonElement(row.payload_json))
            put("provenance", Json.parseToJsonElement(row.provenance_json))
            put("resultRef", row.result_ref)
            put("contentHash", row.content_hash)
            put("signature", row.signature)
            put("correctsEventId", row.corrects_event_id)
        }
    }

    private fun knowledge(target: ExitTarget, id: String): JsonObject {
        val row = requireNotNull(db.schemaQueries.selectKnowledgeById(id).executeAsOneOrNull())
        require(JsonMapping.provenance(row.provenance_json).author == SubjectRef.Person(target.person))
        val experiences = Json.parseToJsonElement(row.derived_experience_json).jsonArray
        require(experiences.isNotEmpty())
        experiences.forEach { reference ->
            val experience = requireNotNull(db.exitQueries
                .selectExitExperience(reference.jsonPrimitive.content).executeAsOneOrNull())
            val events = Json.parseToJsonElement(experience.derived_events_json).jsonArray
            require(events.isNotEmpty())
            events.forEach { event(target, it.jsonPrimitive.content) }
        }
        return buildJsonObject {
            put("id", row.knowledge_id)
            put("statement", row.statement)
            put("derivedExperience", experiences)
            put("evidence", Json.parseToJsonElement(row.evidence_json))
            put("context", Json.parseToJsonElement(row.context_json))
            put("status", row.status)
            put("version", row.version)
            put("supersedes", row.supersedes)
            put("provenance", Json.parseToJsonElement(row.provenance_json))
        }
    }

    private fun evidence(target: ExitTarget, id: String): JsonObject {
        val row = requireNotNull(db.exitQueries.selectOwnedEvidence(id).executeAsOneOrNull())
        require(row.person_id == target.person.value && row.core_id == target.core.value)
        row.source_event_id?.let { event(target, it) }
        return buildJsonObject {
            put("id", row.evidence_id)
            put("description", row.description)
            put("sourceEventId", row.source_event_id)
            put("recordedAt", row.recorded_at)
        }
    }
}
