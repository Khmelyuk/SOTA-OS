// Authoring only: compile in revision 3c1a4aa's test module, never against current production code.
package sotaos.test.acceptance

import kotlinx.serialization.json.*
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.domain.collective.*
import sotaos.domain.collective.Core
import sotaos.domain.collective.Membership
import sotaos.domain.memory.*
import sotaos.domain.memory.Event
import sotaos.domain.memory.Experience
import sotaos.domain.memory.Knowledge
import sotaos.domain.relation.*
import sotaos.domain.relation.Authority
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectory(output)
    ExitFixture().use { f ->
        f.addUnaffectedCore()
        f.addTypedHistory()
        f.approveSnapshots()
        val signer = Ed25519EventSigner.generate()
        f.repos.actorSigningKeys.save(ActorSigningKeyRecord(f.invocation.actor,
            "historical-exit-key", signer.publicKeyEncoded()), f.now)
        val signed = SignedP10Runtime(f.store, LocalSyncIdentity(SotaId("historical-exit-node"),
            setOf(f.invocation.actor)), Context("exit-governance"), Clock { f.now },
            IdGenerator { UUID.randomUUID().toString() }, { signer })
        val service = signed.exit.service
        val completed = service.requestExit(f.invocation, f.core).id
        service.revokeActiveDelegations(f.invocation, completed)
        service.closeRelations(f.invocation, completed)
        service.settleObligations(f.invocation, completed)
        val archive = service.exportPortableData(f.invocation, completed)
        service.terminateParticipation(f.invocation, completed, archive.sha256)
        val pending = f.runtime.service.requestExit(f.invocation, f.other).id
        val codec = JsonSyncRecordCodec()
        val records = SqlDelightSyncRepository(f.store.database, codec).records()
        writeJson(output.resolve("vectors.json"), buildJsonObject {
            put("fixtureOnly", true); put("completedExitId", completed); put("pendingExitId", pending)
            put("records", JsonArray(records.map { JsonPrimitive(codec.encode(it)) }))
        })
        captureDatabase(f.path, output)
    }
}

private fun ExitFixture.addUnaffectedCore() {
    val id = CoreId("unaffected")
    repos.cores.save(Core(id, id.value, now, LifecycleState.ACTIVE))
    repos.memberships.save(Membership(person, SubjectRef.Core(id), null, now, null, LifecycleState.ACTIVE))
    repos.authorities.save(authority(id))
    val target = ExitTarget(person, id)
    inventory.addRelation(CoreExitRelation("relation-unaffected", target, "TRUST", "Unchanged relation"))
    inventory.addObligation(ExitObligation("obligation-unaffected", target, "Unchanged obligation", ObligationState.OPEN))
}

private fun ExitFixture.addTypedHistory() {
    val relations = SqlDelightRelationInventory(store.database)
    relations.registerTrust("typed-trust", target, Trust(invocation.actor, SubjectRef.Core(core), Context("work"),
        Scope(setOf("review")), listOf(EvidenceId("owned-evidence")), Validity(now, null), LifecycleState.ACTIVE))
    relations.registerAgreement("typed-agreement", target, Agreement(listOf(invocation.actor, SubjectRef.Core(core)),
        "Shared historical project", listOf("Return equipment"), Validity(now, null),
        "Participation ends; equipment responsibility remains", LifecycleState.ACTIVE),
        AgreementExitPolicy.WITHDRAW_PARTICIPATION_RETAIN_OBLIGATIONS,
        listOf(ExitObligation("equipment", target, "Повернути обладнання", ObligationState.OPEN)))
    inventory.addObligation(ExitObligation("already-fulfilled", target, "Completed work", ObligationState.OPEN))
}

private fun ExitFixture.approveSnapshots() {
    val owner = invocation.actor
    repos.events.append(Event(EventId("owned-event"), "OWNED", owner, now, Context("work"),
        "authority-chosen", null, mapOf("note" to "Історичні дані\nЗберегти точно"), null,
        Provenance(null, owner, now), "historical-unsigned-content", null))
    val provenance = Provenance(EventId("owned-event"), owner, now)
    repos.experiences.save(Experience(ExperienceId("owned-experience"), listOf(EventId("owned-event")),
        Context("work"), "Attempt", "Outcome", "Conclusion", "Conditions", Confidence.HIGH, provenance))
    repos.knowledge.save(Knowledge(KnowledgeId("owned-knowledge"), "Approved historical statement",
        listOf(ExperienceId("owned-experience")), listOf(EvidenceId("owned-evidence")), Context("work"),
        KnowledgeStatus.CANDIDATE, 1, null, provenance))
    inventory.saveEvidence(target, Evidence(EvidenceId("owned-evidence"), "Historical evidence", EventId("owned-event"), now))
    val operator = ProtocolInvocation(SubjectRef.Person(PersonId("operator")),
        "Reviewed historical export and fulfillment", Context("exit-governance"))
    val refs = listOf(ExitArtifactRef(ExitArtifactKind.EVENT, "owned-event"),
        ExitArtifactRef(ExitArtifactKind.KNOWLEDGE, "owned-knowledge"),
        ExitArtifactRef(ExitArtifactKind.EVIDENCE, "owned-evidence"))
    val grant = Authority(AuthorityId("exit-operator"), SubjectRef.Core(core), operator.actor,
        Scope(setOf("exit.export", "exit.settle"), refs.map { "${it.kind}:${it.id}" }.toSet() +
            setOf("core:chosen", "obligation:already-fulfilled", "obligation:equipment")),
        operator.context!!, AuthorityBasis(note = "Synthetic independent operator mandate"),
        Validity(now.minusSeconds(1), null), SubjectRef.Core(core), LifecycleState.ACTIVE)
    repos.authorities.save(grant)
    refs.forEach { runtime.governance.allowExport(operator, target, it, grant.id) }
    runtime.governance.fulfillObligation(operator, target, "already-fulfilled", "review:completed-work", grant.id)
}

private fun captureDatabase(path: Path, output: Path) {
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { check(!it.next()) }
        }
        val objects = mutableListOf<JsonObject>()
        connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT type, name, sql FROM sqlite_master
                WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
                ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 ELSE 2 END, name""").use { result ->
                while (result.next()) objects += buildJsonObject {
                    put("type", result.getString(1)); put("name", result.getString(2)); put("sql", result.getString(3))
                }
            }
        }
        writeJson(output.resolve("runtime-schema.json"), buildJsonObject {
            put("revision", "3c1a4aa1d20cf77179e3c37580ddd682b329d018")
            put("capture", "sqlite_master after historical signed completed P10 and unsigned pending P10")
            put("objects", JsonArray(objects)); put("statements", JsonArray(objects.map { it.getValue("sql") }))
        })
        val rows = mutableListOf<JsonObject>()
        for (table in objects.filter { it.getValue("type").jsonPrimitive.content == "table" }) {
            val name = table.getValue("name").jsonPrimitive.content
            check(name.matches(Regex("[a-z_]+")))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $name ORDER BY rowid").use { result ->
                    while (result.next()) rows += buildJsonObject {
                        put("table", name)
                        put("values", buildJsonObject {
                            for (index in 1..result.metaData.columnCount) {
                                val value = result.getObject(index)
                                put(result.metaData.getColumnName(index), when (value) {
                                    null -> JsonNull
                                    is Number -> JsonPrimitive(value)
                                    else -> JsonPrimitive(value.toString())
                                })
                            }
                        })
                    }
                }
            }
        }
        writeJson(output.resolve("data.json"), buildJsonObject { put("fixtureOnly", true); put("rows", JsonArray(rows)) })
        println("Captured ${objects.size} schema objects and ${rows.size} synthetic P10 rows")
    }
}

private fun writeJson(path: Path, value: JsonObject) {
    Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value) + "\n")
}
