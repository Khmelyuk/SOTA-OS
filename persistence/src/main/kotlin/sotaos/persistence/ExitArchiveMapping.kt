package sotaos.persistence

import kotlinx.serialization.json.*
import sotaos.application.ports.*
import sotaos.persistence.db.SotaOsDatabase
import java.security.MessageDigest

internal fun exitDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal class ExitArchiveMapping(private val db: SotaOsDatabase) {
    fun create(process: ExitProcess): ExitDocument {
        val target = process.target
        val grants = db.exitQueries.selectExportGrants(target.person.value, target.core.value).executeAsList()
        val document = buildJsonObject {
            put("format", "SOTA-P10-v1")
            put("exitId", process.id)
            put("personId", target.person.value)
            put("coreId", target.core.value)
            put("requestedAt", process.requestedAt.toString())
            put("policy", "explicit-approved-snapshots-v1")
            put("identities", JsonArray(db.schemaQueries.selectIdentitiesByPerson(target.person.value)
                .executeAsList().map { identity ->
                    buildJsonObject {
                        put("id", identity.identity_id)
                        put("localHandle", identity.local_handle)
                        put("externalRefs", Json.parseToJsonElement(identity.external_refs_json))
                    }
                }))
            ExitArtifactKind.entries.forEach { kind ->
                put(kind.name.lowercase(), JsonArray(grants.filter { it.kind == kind.name }
                    .map { Json.parseToJsonElement(it.artifact_json) }))
            }
            put("obligations", JsonArray(db.exitQueries.selectObligations(target.person.value, target.core.value)
                .executeAsList().map { obligation ->
                    buildJsonObject {
                        put("id", obligation.obligation_id)
                        put("description", obligation.description)
                        put("state", obligation.state)
                        put("resolution", obligation.resolution)
                    }
                }))
        }.toString()
        return ExitDocument(process.id, target, document, exitDigest(document))
    }
}
