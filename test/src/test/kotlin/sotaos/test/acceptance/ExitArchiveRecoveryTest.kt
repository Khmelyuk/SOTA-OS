package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.exit.P10Runtime
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.security.Ed25519EventSigner
import sotaos.security.LocalSyncIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

class ExitArchiveRecoveryTest : FunSpec({
    for (completed in listOf(false, true)) {
        test("frozen archive is privately re-delivered after restart without lifecycle changes completed=$completed") {
            withArchiveFixture { f, directory ->
                val id = f.prepare()
                val document = f.runtime.service.exportPortableData(f.invocation, id)
                if (completed) f.runtime.service.terminateParticipation(f.invocation, id, document.sha256)
                f.reopen()
                val before = Files.readAllBytes(f.path).toList()
                repeat(2) { attempt ->
                    val destination = directory.resolve("copy-$attempt.json")
                    f.runtime.copyArchive(f.invocation, f.core, id, destination) shouldBe document
                    Files.readAllBytes(destination).toList() shouldBe document.json.toByteArray(Charsets.UTF_8).toList()
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(destination)) shouldBe "rw-------"
                }
                f.scope.hasMembership(f.target) shouldBe !completed
                f.inventory.obligations(f.target).single().state shouldBe ObligationState.RETAINED
                Files.readAllBytes(f.path).toList() shouldBe before
            }
        }
    }
    test("archive recovery never prepares an export or advances an incomplete exit") {
        withArchiveFixture { f, directory ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            for (stage in listOf(ExitStage.REQUESTED, ExitStage.OBLIGATIONS_SETTLED)) {
                if (stage == ExitStage.OBLIGATIONS_SETTLED) f.prepare()
                val before = Files.readAllBytes(f.path).toList()
                val destination = directory.resolve("absent.json")
                shouldThrow<IllegalArgumentException> { f.runtime.copyArchive(f.invocation, f.core, id, destination) }
                Files.exists(destination) shouldBe false
                f.exits.find(id)?.stage shouldBe stage
                f.exits.export(id) shouldBe null
                Files.readAllBytes(f.path).toList() shouldBe before
            }
        }
    }
    test("Person Core exit binding and current rights are checked before creating a file") {
        withArchiveFixture { f, directory ->
            val id = f.prepare()
            f.runtime.service.exportPortableData(f.invocation, id)
            val destination = directory.resolve("absent.json")
            val before = Files.readAllBytes(f.path).toList()
            val stranger = f.invocation.copy(actor = SubjectRef.Person(PersonId("stranger")))
            val hidden = shouldThrow<IllegalArgumentException> {
                f.runtime.copyArchive(stranger, f.core, id, destination)
            }
            val unknown = shouldThrow<IllegalArgumentException> {
                f.runtime.copyArchive(f.invocation, f.core, "unknown", destination)
            }
            hidden.message shouldBe unknown.message
            shouldThrow<IllegalArgumentException> { f.runtime.copyArchive(f.invocation, f.other, id, destination) }
            shouldThrow<IllegalArgumentException> {
                f.runtime.copyArchive(f.invocation.copy(actor = SubjectRef.Agent(AgentId("tool"))),
                    f.core, id, destination)
            }
            val denied = P10Runtime(f.store, Clock { f.now }, IdGenerator { error("No new IDs") },
                RightsConstraint {
                    it.operation shouldBe "readArchive"
                    it.scope shouldBe Scope(setOf("readArchive"), setOf("core:${f.core.value}", "exit:$id"))
                    throw RightsConstraintViolation("DENY", "test policy")
                })
            shouldThrow<RightsConstraintViolation> { denied.copyArchive(f.invocation, f.core, id, destination) }
            Files.exists(destination) shouldBe false
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("existing paths dangling symlinks and failed delivery cannot change archive or membership") {
        withArchiveFixture { f, directory ->
            val id = f.prepare()
            f.runtime.service.exportPortableData(f.invocation, id)
            val occupied = directory.resolve("existing.json")
            Files.writeString(occupied, "keep")
            val missing = directory.resolve("missing.json")
            val link = directory.resolve("link.json")
            Files.createSymbolicLink(link, missing)
            val before = Files.readAllBytes(f.path).toList()
            for (destination in listOf(occupied, link, directory.resolve("absent-dir/copy.json"))) {
                shouldThrow<Exception> { f.runtime.copyArchive(f.invocation, f.core, id, destination) }
            }
            Files.readString(occupied) shouldBe "keep"
            Files.isSymbolicLink(link) shouldBe true
            Files.exists(missing) shouldBe false
            f.scope.hasMembership(f.target) shouldBe true
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("a damaged or missing archive is rejected instead of being copied or regenerated") {
        withArchiveFixture { f, directory ->
            val id = f.prepare()
            f.runtime.service.exportPortableData(f.invocation, id)
            // Deliberately bypass the guard only in this disposable corruption fixture.
            f.driver.execute(null, "DROP TRIGGER exit_archive_no_update", 0)
            f.driver.execute(null, "UPDATE exit_archive SET sha256 = 'damaged'", 0)
            val before = Files.readAllBytes(f.path).toList()
            val destination = directory.resolve("absent.json")
            shouldThrow<IllegalArgumentException> { f.runtime.copyArchive(f.invocation, f.core, id, destination) }
                .message shouldBe "Stored exit archive checksum mismatch."
            Files.exists(destination) shouldBe false
            Files.readAllBytes(f.path).toList() shouldBe before
            f.driver.execute(null, "DROP TRIGGER exit_archive_no_delete", 0)
            f.driver.execute(null, "DELETE FROM exit_archive", 0)
            val missing = Files.readAllBytes(f.path).toList()
            shouldThrow<IllegalArgumentException> { f.runtime.copyArchive(f.invocation, f.core, id, destination) }
                .message shouldBe "The stored exit archive is missing."
            Files.exists(destination) shouldBe false
            Files.readAllBytes(f.path).toList() shouldBe missing
        }
    }
    test("retired signer does not prevent copying signed history or create an unsigned transition") {
        withArchiveFixture { f, directory ->
            val signer = Ed25519EventSigner.generate()
            f.repos.actorSigningKeys.save(ActorSigningKeyRecord(f.invocation.actor, "archive-key",
                signer.publicKeyEncoded()), f.now)
            val signed = SignedP10Runtime(f.store, LocalSyncIdentity(SotaId("source"), setOf(f.invocation.actor)),
                Context("governance"), Clock { f.now }, IdGenerator { UUID.randomUUID().toString() }, { signer })
            val service = signed.exit.service
            val id = service.requestExit(f.invocation, f.core).id
            service.revokeActiveDelegations(f.invocation, id)
            service.closeRelations(f.invocation, id)
            service.settleObligations(f.invocation, id)
            val document = service.exportPortableData(f.invocation, id)
            f.repos.actorSigningKeys.revoke(f.invocation.actor, "archive-key", f.now.plusSeconds(1))
            f.reopen()
            val before = Files.readAllBytes(f.path).toList()
            f.runtime.copyArchive(f.invocation, f.core, id, directory.resolve("copy.json")) shouldBe document
            f.exits.transitions(id).size shouldBe 5
            f.exits.find(id)?.stage shouldBe ExitStage.EXPORT_READY
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
})

private fun withArchiveFixture(block: (ExitFixture, Path) -> Unit) {
    val directory = Files.createTempDirectory("p10-archive-recovery-")
    try { ExitFixture().use { block(it, directory) } } finally {
        Files.list(directory).use { paths -> paths.forEach { Files.delete(it) } }
        Files.delete(directory)
    }
}
