package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.exit.P10Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import java.nio.file.Files
import java.nio.file.Path

class ExitArchiveVerificationTest : FunSpec({
    for (completed in listOf(false, true)) {
        test("verify delivered archive after restart preserves file and database completed=$completed") {
            withVerificationFixture { f, directory ->
                val id = f.prepare()
                val document = f.runtime.service.exportPortableData(f.invocation, id)
                val file = directory.resolve("archive.json")
                f.runtime.copyArchive(f.invocation, f.core, id, file)
                if (completed) f.runtime.service.terminateParticipation(f.invocation, id, document.sha256)
                f.reopen()
                val before = Files.readAllBytes(f.path).toList()
                val modified = Files.getLastModifiedTime(file)
                val permissions = Files.getPosixFilePermissions(file)
                repeat(2) { f.runtime.verifyArchive(f.invocation, f.core, id, file) shouldBe document }
                Files.readAllBytes(file).toList() shouldBe document.json.toByteArray(Charsets.UTF_8).toList()
                Files.getLastModifiedTime(file) shouldBe modified
                Files.getPosixFilePermissions(file) shouldBe permissions
                f.scope.hasMembership(f.target) shouldBe !completed
                Files.readAllBytes(f.path).toList() shouldBe before
            }
        }
    }
    test("modified truncated appended and empty files fail without repairing them") {
        withVerificationFixture { f, directory ->
            val id = f.prepare()
            val document = f.runtime.service.exportPortableData(f.invocation, id)
            val bytes = document.json.toByteArray(Charsets.UTF_8)
            val changed = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val before = Files.readAllBytes(f.path).toList()
            for (data in listOf(changed, bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(10), byteArrayOf())) {
                val file = directory.resolve("damaged.json")
                Files.write(file, data)
                shouldThrow<IllegalArgumentException> { f.runtime.verifyArchive(f.invocation, f.core, id, file) }
                Files.readAllBytes(file).toList() shouldBe data.toList()
                Files.readAllBytes(f.path).toList() shouldBe before
            }
        }
    }
    test("missing files directories and final symlinks are rejected without creating a file") {
        withVerificationFixture { f, directory ->
            val id = f.prepare()
            val document = f.runtime.service.exportPortableData(f.invocation, id)
            val file = directory.resolve("archive.json")
            Files.writeString(file, document.json)
            val missing = directory.resolve("missing.json")
            val link = Files.createSymbolicLink(directory.resolve("link.json"), file)
            val dangling = Files.createSymbolicLink(directory.resolve("dangling.json"), missing)
            val before = Files.readAllBytes(f.path).toList()
            for (candidate in listOf(missing, directory, link, dangling)) {
                shouldThrow<IllegalArgumentException> { f.runtime.verifyArchive(f.invocation, f.core, id, candidate) }
            }
            Files.exists(missing) shouldBe false
            Files.isSymbolicLink(link) shouldBe true
            Files.isSymbolicLink(dangling) shouldBe true
            Files.readString(file) shouldBe document.json
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("ownership and current readArchive policy are checked before the file is inspected") {
        withVerificationFixture { f, directory ->
            val id = f.prepare()
            f.runtime.service.exportPortableData(f.invocation, id)
            val missing = directory.resolve("missing.json")
            val before = Files.readAllBytes(f.path).toList()
            val stranger = f.invocation.copy(actor = SubjectRef.Person(PersonId("stranger")))
            for ((actor, core, selected) in listOf(Triple(stranger, f.core, id),
                Triple(f.invocation, f.other, id), Triple(f.invocation, f.core, "unknown"))) {
                shouldThrow<IllegalArgumentException> { f.runtime.verifyArchive(actor, core, selected, missing) }
                    .message shouldBe "No exit archive for this Person/Core/exit."
            }
            shouldThrow<IllegalArgumentException> {
                f.runtime.verifyArchive(f.invocation.copy(actor = SubjectRef.Agent(AgentId("tool"))),
                    f.core, id, missing)
            }.message shouldBe "Only a Person may read own exit archive."
            val denied = P10Runtime(f.store, Clock { f.now }, IdGenerator { error("No new IDs") },
                RightsConstraint {
                    it.operation shouldBe "readArchive"
                    it.scope shouldBe Scope(setOf("readArchive"), setOf("core:${f.core.value}", "exit:$id"))
                    throw RightsConstraintViolation("DENY", "test policy")
                })
            shouldThrow<RightsConstraintViolation> { denied.verifyArchive(f.invocation, f.core, id, missing) }
            Files.exists(missing) shouldBe false
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("verification requires an existing intact prepared archive") {
        withVerificationFixture { f, directory ->
            val id = f.prepare()
            val file = directory.resolve("archive.json")
            val before = Files.readAllBytes(f.path).toList()
            shouldThrow<IllegalArgumentException> { f.runtime.verifyArchive(f.invocation, f.core, id, file) }
                .message shouldBe "The exit has no prepared archive."
            Files.readAllBytes(f.path).toList() shouldBe before
            val document = f.runtime.service.exportPortableData(f.invocation, id)
            Files.writeString(file, document.json)
            // Corrupt only this disposable fixture; production archives remain append-only.
            f.driver.execute(null, "DROP TRIGGER exit_archive_no_update", 0)
            f.driver.execute(null, "UPDATE exit_archive SET sha256 = 'damaged'", 0)
            val damaged = Files.readAllBytes(f.path).toList()
            shouldThrow<IllegalArgumentException> { f.runtime.verifyArchive(f.invocation, f.core, id, file) }
                .message shouldBe "Stored exit archive checksum mismatch."
            Files.readString(file) shouldBe document.json
            Files.readAllBytes(f.path).toList() shouldBe damaged
        }
    }
})

private fun withVerificationFixture(block: (ExitFixture, Path) -> Unit) {
    val directory = Files.createTempDirectory("p10-archive-verification-")
    try { ExitFixture().use { block(it, directory) } } finally {
        Files.list(directory).use { paths -> paths.forEach { Files.delete(it) } }
        Files.delete(directory)
    }
}
