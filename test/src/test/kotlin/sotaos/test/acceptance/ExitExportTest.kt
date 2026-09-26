package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.application.ports.*
import sotaos.domain.memory.*
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*

class ExitExportTest : FunSpec({
    test("export is denied by default and requires scoped independent authority") {
        ExitFixture().use { f ->
            val event = f.ownedEvent("owned", f.core)
            f.repos.events.append(event)
            val ref = ExitArtifactRef(ExitArtifactKind.EVENT, event.id.value)
            val authority = f.exportAuthority(setOf("EVENT:owned"))
            f.repos.authorities.save(authority)
            shouldThrow<IllegalArgumentException> {
                f.runtime.governance.allowExport(f.invocation, f.target, ref, authority.id)
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.governance.allowExport(f.operatorInvocation(), f.target,
                    ref.copy(id = "outside-scope"), authority.id)
            }
            val doc = f.runtime.service.exportPortableData(f.invocation, f.prepare())
            Json.parseToJsonElement(doc.json).jsonObject.getValue("event").jsonArray.size shouldBe 0
        }
    }
    test("approved event knowledge and evidence snapshots survive restart and later edits") {
        ExitFixture().use { f ->
            f.repos.events.append(f.ownedEvent("owned", f.core))
            val provenance = Provenance(null, f.invocation.actor, f.now)
            f.repos.experiences.save(Experience(ExperienceId("experience"), listOf(EventId("owned")),
                Context("work"), "attempt", "outcome", "conclusion", "context", Confidence.HIGH, provenance))
            val knowledge = Knowledge(KnowledgeId("knowledge"), "approved text", listOf(ExperienceId("experience")),
                listOf(EvidenceId("evidence")), Context("work"), KnowledgeStatus.CANDIDATE, 1, null, provenance)
            f.repos.knowledge.save(knowledge)
            f.inventory.saveEvidence(f.target,
                Evidence(EvidenceId("evidence"), "owned evidence", EventId("owned"), f.now))
            val refs = listOf(ExitArtifactRef(ExitArtifactKind.EVENT, "owned"),
                ExitArtifactRef(ExitArtifactKind.KNOWLEDGE, "knowledge"),
                ExitArtifactRef(ExitArtifactKind.EVIDENCE, "evidence"))
            val authority = f.exportAuthority(refs.map { "${it.kind}:${it.id}" }.toSet())
            f.repos.authorities.save(authority)
            refs.forEach { f.runtime.governance.allowExport(f.operatorInvocation(), f.target, it, authority.id) }
            f.repos.knowledge.save(knowledge.copy(statement = "unapproved edit", version = 2))
            f.reopen()
            val doc = f.runtime.service.exportPortableData(f.invocation, f.prepare())
            val json = Json.parseToJsonElement(doc.json).jsonObject
            json.getValue("event").jsonArray.size shouldBe 1
            json.getValue("evidence").jsonArray.size shouldBe 1
            json.getValue("knowledge").jsonArray.single().jsonObject
                .getValue("statement").jsonPrimitive.content shouldBe "approved text"
            json.getValue("identities").jsonArray.size shouldBe 1
            doc.json.contains("unapproved edit") shouldBe false
        }
    }
    test("explicit scope never exports another Core or another Person event") {
        ExitFixture().use { f ->
            f.repos.events.append(f.ownedEvent("other-core", f.other))
            f.repos.events.append(f.ownedEvent("other-person", f.core)
                .copy(actor = SubjectRef.Person(PersonId("other"))))
            val authority = f.exportAuthority(setOf("EVENT:other-core", "EVENT:other-person"))
            f.repos.authorities.save(authority)
            listOf("other-core", "other-person").forEach { id ->
                shouldThrow<IllegalArgumentException> {
                    f.runtime.governance.allowExport(f.operatorInvocation(), f.target,
                        ExitArtifactRef(ExitArtifactKind.EVENT, id), authority.id)
                }
            }
        }
    }
    test("fulfillment requires separate authority and evidence while self exit retains responsibility") {
        ExitFixture().use { f ->
            val authority = f.exportAuthority(setOf("obligation:obligation-chosen"))
            f.repos.authorities.save(authority)
            shouldThrow<IllegalArgumentException> {
                f.runtime.governance.fulfillObligation(f.invocation, f.target, "obligation-chosen",
                    "evidence-reference", authority.id)
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.governance.fulfillObligation(f.operatorInvocation(), f.target,
                    "obligation-chosen", "", authority.id)
            }
            f.runtime.governance.fulfillObligation(f.operatorInvocation(), f.target,
                "obligation-chosen", "evidence-reference", authority.id)
            f.prepare()
            f.inventory.obligations(f.target).single().state shouldBe ObligationState.FULFILLED
        }
    }
})

private fun ExitFixture.operatorInvocation() = ProtocolInvocation(SubjectRef.Person(PersonId("operator")),
    "Approve explicitly reviewed export", Context("exit-governance"))

private fun ExitFixture.exportAuthority(resources: Set<String>) = Authority(
    AuthorityId("export-authority"), SubjectRef.Core(core), operatorInvocation().actor,
    Scope(setOf("exit.export", "exit.settle"), resources + "core:${core.value}"),
    operatorInvocation().context!!, AuthorityBasis(note = "Independent operator"),
    Validity(now.minusSeconds(1), null), SubjectRef.Core(core), LifecycleState.ACTIVE
)

private fun ExitFixture.ownedEvent(id: String, core: CoreId) = Event(
    EventId(id), "OWNED", invocation.actor, now, Context("work"), "authority-${core.value}",
    null, mapOf("owned" to "private data"), null, Provenance(null, invocation.actor, now), "hash", null
)
