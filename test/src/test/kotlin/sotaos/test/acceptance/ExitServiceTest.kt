package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.domain.shared.*
import java.nio.file.Files

class ExitServiceTest : FunSpec({
    test("durable exit resumes after restart and affects only the selected Core") {
        ExitFixture().use { f ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.reopen()
            f.runtime.service.requestExit(f.invocation, f.core).id shouldBe id
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.runtime.service.closeRelations(f.invocation, id)
            f.runtime.service.settleObligations(f.invocation, id)
            val document = f.runtime.service.exportPortableData(f.invocation, id)
            f.reopen()
            f.runtime.service.exportPortableData(f.invocation, id) shouldBe document
            f.runtime.service.terminateParticipation(f.invocation, id, document.sha256)
            f.runtime.service.terminateParticipation(f.invocation, id, document.sha256)
            f.scope.hasMembership(f.target) shouldBe false
            f.scope.hasMembership(f.otherTarget) shouldBe true
            f.scope.hasDelegations(f.target) shouldBe false
            f.scope.hasDelegations(f.otherTarget) shouldBe true
            f.scope.hasRelations(f.target) shouldBe false
            f.scope.hasRelations(f.otherTarget) shouldBe true
            f.inventory.obligations(f.target).single().state shouldBe ObligationState.RETAINED
            f.inventory.obligations(f.otherTarget).single().state shouldBe ObligationState.OPEN
            f.exits.transitions(id).map { it.stage } shouldBe ExitStage.entries
            f.repos.events.findByActor(f.invocation.actor).size shouldBe 6
            f.repos.persons.findById(f.person)?.id shouldBe f.person
        }
    }
    test("self exit rejects another actor and incomplete stages") {
        ExitFixture().use { f ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            shouldThrow<IllegalArgumentException> {
                f.runtime.service.closeRelations(f.invocation, id)
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.service.revokeActiveDelegations(f.invocation.copy(actor =
                    SubjectRef.Person(PersonId("intruder"))), id)
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.service.terminateParticipation(f.invocation, id, "wrong")
            }
            f.exits.find(id)?.stage shouldBe ExitStage.REQUESTED
        }
    }
    test("failed archive insertion leaves membership active and can resume") {
        ExitFixture().use { f ->
            val id = f.prepare()
            f.failOn("exit_archive")
            shouldThrow<Exception> { f.runtime.service.exportPortableData(f.invocation, id) }
            f.exits.find(id)?.stage shouldBe ExitStage.OBLIGATIONS_SETTLED
            f.exits.export(id) shouldBe null
            f.scope.hasMembership(f.target) shouldBe true
            f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
            f.runtime.service.exportPortableData(f.invocation, id).exitId shouldBe id
        }
    }
    test("failed audit rolls back delegation revocation and membership termination") {
        ExitFixture().use { f ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.failOn("exit_transition")
            shouldThrow<Exception> { f.runtime.service.revokeActiveDelegations(f.invocation, id) }
            f.scope.hasDelegations(f.target) shouldBe true
            f.exits.find(id)?.stage shouldBe ExitStage.REQUESTED
            f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
            f.prepare()
            val doc = f.runtime.service.exportPortableData(f.invocation, id)
            f.failOn("exit_transition")
            shouldThrow<Exception> { f.runtime.service.terminateParticipation(f.invocation, id, doc.sha256) }
            f.scope.hasMembership(f.target) shouldBe true
            f.exits.find(id)?.stage shouldBe ExitStage.EXPORT_READY
        }
    }
    test("delivery failure never terminates membership and retry uses durable archive") {
        ExitFixture().use { f ->
            val directory = Files.createTempDirectory("exit-delivery")
            val occupied = directory.resolve("occupied.json")
            Files.writeString(occupied, "existing")
            shouldThrow<Exception> { f.runtime.leave(f.invocation, f.core, occupied) }
            Files.readString(occupied) shouldBe "existing"
            f.scope.hasMembership(f.target) shouldBe true
            val destination = directory.resolve("export.json")
            val receipt = f.runtime.leave(f.invocation, f.core, destination)
            Files.readString(destination) shouldBe f.exits.export(receipt.exitId)?.json
            f.scope.hasMembership(f.target) shouldBe false
            java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(destination)) shouldBe "rw-------"
            Files.delete(destination)
            Files.delete(occupied)
            Files.delete(directory)
        }
    }
    test("pending exit blocks regrant and new commitments") {
        ExitFixture().use { f ->
            f.prepare()
            shouldThrow<Exception> { f.repos.authorities.save(f.authority(f.core)) }
            shouldThrow<Exception> {
                f.inventory.addRelation(CoreExitRelation("new", f.target, "TRUST", "new"))
            }
            shouldThrow<Exception> {
                f.inventory.addObligation(ExitObligation("new", f.target, "new", ObligationState.OPEN))
            }
        }
    }
    test("archive and audit are append only and digest acknowledgement is mandatory") {
        ExitFixture().use { f ->
            val id = f.prepare()
            f.runtime.service.exportPortableData(f.invocation, id)
            shouldThrow<IllegalArgumentException> {
                f.runtime.service.terminateParticipation(f.invocation, id, "not-the-export")
            }
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM exit_archive", 0) }
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM exit_transition", 0) }
            f.scope.hasMembership(f.target) shouldBe true
        }
    }
    test("relation and obligation mutations roll back together with their stage audit") {
        ExitFixture().use { f ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            f.runtime.service.revokeActiveDelegations(f.invocation, id)
            f.failOn("exit_transition")
            shouldThrow<Exception> { f.runtime.service.closeRelations(f.invocation, id) }
            f.scope.hasRelations(f.target) shouldBe true
            f.driver.execute(null, "DROP TRIGGER injected_failure", 0)
            f.runtime.service.closeRelations(f.invocation, id)
            f.failOn("exit_transition")
            shouldThrow<Exception> { f.runtime.service.settleObligations(f.invocation, id) }
            f.inventory.obligations(f.target).single().state shouldBe ObligationState.OPEN
            f.exits.find(id)?.stage shouldBe ExitStage.RELATIONS_CLOSED
        }
    }
    test("RightsConstraint denial cannot create or advance an exit") {
        ExitFixture().use { f ->
            val denied = sotaos.api.exit.P10Runtime(f.store, Clock { f.now }, IdGenerator { "denied" },
                RightsConstraint { throw RightsConstraintViolation("DENY", "test policy") })
            shouldThrow<RightsConstraintViolation> { denied.service.requestExit(f.invocation, f.core) }
            f.exits.findOpen(f.target) shouldBe null
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            shouldThrow<RightsConstraintViolation> { denied.service.revokeActiveDelegations(f.invocation, id) }
            f.scope.hasDelegations(f.target) shouldBe true
        }
    }
    test("additive exit migration preserves existing Core membership and identity") {
        ExitFixture().use { f ->
            val tables = listOf("exit_governance_audit", "owned_exit_evidence", "exit_export_grant",
                "exit_obligation", "core_exit_relation", "exit_archive", "exit_transition", "exit_process")
            listOf("authority", "membership").forEach { table ->
                listOf("insert", "update").forEach { operation ->
                    f.driver.execute(null, "DROP TRIGGER ${table}_exit_guard_${operation}", 0)
                }
            }
            tables.forEach { f.driver.execute(null, "DROP TABLE $it", 0) }
            f.reopen()
            f.scope.hasMembership(f.target) shouldBe true
            f.repos.persons.findById(f.person)?.id shouldBe f.person
            f.exits.findOpen(f.target) shouldBe null
            f.runtime.service.requestExit(f.invocation, f.core).stage shouldBe ExitStage.REQUESTED
        }
    }

})
