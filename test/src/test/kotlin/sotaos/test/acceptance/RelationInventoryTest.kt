package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.application.ports.*
import sotaos.domain.relation.Agreement
import sotaos.domain.relation.Trust
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRelationInventory

class RelationInventoryTest : FunSpec({
    test("typed terms survive restart and exit closes participation without fulfilling obligations") {
        ExitFixture().use { f ->
            val agreement = f.agreement()
            val trust = f.trust()
            f.relations().registerTrust("typed-trust", f.target, trust)
            f.registerAgreement(agreement)
            f.reopen()
            f.relations().trusts(f.target).single().terms shouldBe trust
            f.relations().agreements(f.target).single().terms shouldBe agreement
            val id = f.prepare()
            val stored = f.relations().agreements(f.target).single()
            stored.terms shouldBe agreement
            stored.state shouldBe ParticipationState.CLOSED
            stored.obligations.single().state shouldBe ObligationState.RETAINED
            f.relations().trusts(f.target).single().state shouldBe ParticipationState.CLOSED
            val doc = f.runtime.service.exportPortableData(f.invocation, id)
            val exported = Json.parseToJsonElement(doc.json).jsonObject.getValue("obligations").jsonArray
                .single { it.jsonObject.getValue("id").jsonPrimitive.content == "equipment" }.jsonObject
            exported.getValue("relationId").jsonPrimitive.content shouldBe "agreement"
            f.runtime.service.terminateParticipation(f.invocation, id, doc.sha256)
            f.reopen()
            f.relations().agreements(f.target).single() shouldBe stored
        }
    }
    test("agreement insertion rolls back all terms and obligations on a duplicate obligation") {
        ExitFixture().use { f ->
            shouldThrow<Exception> {
                f.relations().registerAgreement("agreement", f.target, f.agreement(), policy,
                    listOf(f.obligation(), f.obligation()))
            }
            f.relations().agreements(f.target) shouldBe emptyList()
            f.inventory.obligations(f.target).size shouldBe 1
            f.registerAgreement()
        }
    }
    test("pending exit rejects typed imports atomically") {
        ExitFixture().use { f ->
            f.runtime.service.requestExit(f.invocation, f.core)
            shouldThrow<Exception> { f.registerAgreement() }
            shouldThrow<Exception> { f.relations().registerTrust("trust", f.target, f.trust()) }
            f.relations().agreements(f.target) shouldBe emptyList()
            f.relations().trusts(f.target) shouldBe emptyList()
            f.inventory.obligations(f.target).size shouldBe 1
        }
    }
    test("imports reject unrelated participants mismatched obligations and missing exit terms") {
        ExitFixture().use { f ->
            shouldThrow<IllegalArgumentException> { f.registerAgreement(f.agreement().copy(exitTerms = "")) }
            shouldThrow<IllegalArgumentException> {
                f.registerAgreement(f.agreement().copy(parties = listOf(SubjectRef.Core(f.core))))
            }
            shouldThrow<IllegalArgumentException> {
                f.relations().registerAgreement("agreement", f.target, f.agreement(), policy,
                    listOf(f.obligation().copy(target = f.otherTarget)))
            }
            shouldThrow<IllegalArgumentException> {
                f.relations().registerTrust("trust", f.target, f.trust().copy(subject = SubjectRef.Core(f.core)))
            }
            f.relations().agreements(f.target) shouldBe emptyList()
        }
    }
    test("terms links and closed participation cannot be rewritten or deleted") {
        ExitFixture().use { f ->
            f.registerAgreement()
            shouldThrow<Exception> {
                f.driver.execute(null, "UPDATE relation_terms SET terms_json = '{}'", 0)
            }
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM relation_obligation", 0) }
            shouldThrow<Exception> {
                f.driver.execute(null, "DELETE FROM core_exit_relation WHERE relation_id = 'agreement'", 0)
            }
            shouldThrow<Exception> {
                f.driver.execute(null, "UPDATE exit_obligation SET description = 'waived' " +
                    "WHERE obligation_id = 'equipment'", 0)
            }
            shouldThrow<Exception> {
                f.driver.execute(null, "DELETE FROM exit_obligation WHERE obligation_id = 'equipment'", 0)
            }
            f.prepare()
            shouldThrow<Exception> {
                f.driver.execute(null,
                    "UPDATE core_exit_relation SET state = 'ACTIVE' WHERE relation_id = 'agreement'", 0)
            }
            f.relations().agreements(f.target).single().state shouldBe ParticipationState.CLOSED
        }
    }
    test("failed closure audit preserves typed participation and obligations") {
        ExitFixture().use { f ->
            f.registerAgreement()
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.failOn("exit_transition")
            shouldThrow<Exception> { f.runtime.service.closeRelations(f.invocation, id) }
            f.relations().agreements(f.target).single().state shouldBe ParticipationState.ACTIVE
            f.relations().agreements(f.target).single().obligations.single().state shouldBe ObligationState.OPEN
        }
    }
    test("closure affects only the selected Core and completed exit cannot import new participation") {
        ExitFixture().use { f ->
            f.registerAgreement()
            f.relations().registerAgreement("other-agreement", f.otherTarget, f.agreement(), policy,
                listOf(f.obligation().copy(id = "other-equipment", target = f.otherTarget)))
            val id = f.prepare()
            val doc = f.runtime.service.exportPortableData(f.invocation, id)
            f.runtime.service.terminateParticipation(f.invocation, id, doc.sha256)
            f.relations().agreements(f.otherTarget).single().state shouldBe ParticipationState.ACTIVE
            f.relations().agreements(f.otherTarget).single().obligations.single().state shouldBe ObligationState.OPEN
            shouldThrow<IllegalArgumentException> {
                f.relations().registerTrust("late-trust", f.target, f.trust())
            }
        }
    }
    test("additive migration preserves legacy relations without inventing typed terms") {
        ExitFixture().use { f ->
            f.driver.execute(null, "DROP TABLE relation_obligation", 0)
            f.driver.execute(null, "DROP TABLE relation_terms", 0)
            f.reopen()
            f.scope.hasRelations(f.target) shouldBe true
            f.relations().trusts(f.target) shouldBe emptyList()
            f.registerAgreement()
            f.relations().agreements(f.target).size shouldBe 1
        }
    }
})

private val policy = AgreementExitPolicy.WITHDRAW_PARTICIPATION_RETAIN_OBLIGATIONS
private fun ExitFixture.relations() = SqlDelightRelationInventory(store.database)
private fun ExitFixture.agreement() = Agreement(listOf(SubjectRef.Person(person), SubjectRef.Core(core)),
    "Shared project", listOf("Return the equipment"), Validity(now, null),
    "Participation may end; outstanding equipment responsibility remains", LifecycleState.ACTIVE)
private fun ExitFixture.trust() = Trust(SubjectRef.Person(person), SubjectRef.Core(core), Context("work"),
    Scope(setOf("review")), listOf(EvidenceId("evidence")), Validity(now, null), LifecycleState.ACTIVE)
private fun ExitFixture.obligation() = ExitObligation("equipment", target, "Return equipment", ObligationState.OPEN)
private fun ExitFixture.registerAgreement(agreement: Agreement = agreement()) =
    relations().registerAgreement("agreement", target, agreement, policy, listOf(obligation()))
