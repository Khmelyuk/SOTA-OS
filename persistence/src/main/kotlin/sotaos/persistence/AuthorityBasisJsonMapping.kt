package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.domain.agency.*
import sotaos.domain.collective.*
import sotaos.domain.memory.*
import sotaos.domain.relation.*
import sotaos.domain.rights.*
import sotaos.domain.shared.*

import sotaos.persistence.JsonMapping.context
import sotaos.persistence.JsonMapping.scope
import sotaos.persistence.ValueJsonMapping.subject
import sotaos.persistence.ValueJsonMapping.validity

/** JSON conversion for the evidence cited by an Authority. */
internal object AuthorityBasisJsonMapping {
    private val json = Json { encodeDefaults = true }

    fun basis(value: AuthorityBasis): String = encode(buildJsonObject {
        put("trust", JsonArray(value.trustRef.map { trust -> buildJsonObject {
            val (subjectKind, subjectId) = subject(trust.subject)
            val (targetKind, targetId) = subject(trust.target)
            put("subjectKind", subjectKind); put("subjectId", subjectId)
            put("targetKind", targetKind); put("targetId", targetId)
            put("context", parse(context(trust.context))); put("scope", parse(scope(trust.scope)))
            put("evidence", JsonArray(trust.evidence.map { JsonPrimitive(it.value) }))
            put("validity", parse(validity(trust.validity.from, trust.validity.until)))
            put("state", trust.state.name)
        } }))
        put("competence", JsonArray(value.competenceRef.map { competence -> buildJsonObject {
            val (kind, id) = subject(competence.subject)
            put("subjectKind", kind); put("subjectId", id); put("domain", competence.domain)
            put("scope", parse(scope(competence.scope))); put("level", competence.level.name)
            put("evidence", JsonArray(competence.evidence.map { JsonPrimitive(it.value) }))
            put("validity", parse(validity(competence.validity.from, competence.validity.until)))
        } }))
        value.missionRef?.let { put("missionRef", it) }
        put("note", value.note)
    })

    fun basis(value: String): AuthorityBasis {
        val item = parse(value).jsonObject
        val trusts = item.getValue("trust").jsonArray.map { element ->
            val trust = element.jsonObject
            val validity = validity(encode(trust.getValue("validity")))
            Trust(
                subject(trust.getValue("subjectKind").jsonPrimitive.content,
                    trust.getValue("subjectId").jsonPrimitive.content),
                subject(trust.getValue("targetKind").jsonPrimitive.content,
                    trust.getValue("targetId").jsonPrimitive.content),
                context(encode(trust.getValue("context"))), scope(encode(trust.getValue("scope"))),
                trust.getValue("evidence").jsonArray.map { EvidenceId(it.jsonPrimitive.content) }, validity,
                LifecycleState.valueOf(trust.getValue("state").jsonPrimitive.content)
            )
        }
        val competences = item.getValue("competence").jsonArray.map { element ->
            val competence = element.jsonObject
            Competence(
                subject(competence.getValue("subjectKind").jsonPrimitive.content,
                    competence.getValue("subjectId").jsonPrimitive.content),
                competence.getValue("domain").jsonPrimitive.content,
                scope(encode(competence.getValue("scope"))),
                CompetenceLevel.valueOf(competence.getValue("level").jsonPrimitive.content),
                competence.getValue("evidence").jsonArray.map { EvidenceId(it.jsonPrimitive.content) },
                validity(encode(competence.getValue("validity")))
            )
        }
        return AuthorityBasis(trusts, competences,
            item["missionRef"]?.jsonPrimitive?.contentOrNull,
            item["note"]?.jsonPrimitive?.content ?: "")
    }

    private fun encode(value: JsonElement): String = value.toString()
    private fun parse(value: String): JsonElement = json.parseToJsonElement(value)
}
