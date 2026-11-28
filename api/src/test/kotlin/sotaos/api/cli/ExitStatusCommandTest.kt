package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.domain.shared.*
import java.nio.file.Files
import java.time.Instant

class ExitStatusCommandTest : FunSpec({
    test("status rejects mutation signing and Person override options before opening a database") {
        val missing = Files.createTempDirectory("exit-status-args-")
        try {
            val path = missing.resolve("absent.db")
            val args = Arguments("exit", "status", path, mapOf("handle" to "alice", "core" to "chosen"))
            for (option in listOf("out", "person", "id", "node", "signing-keystore", "signing-alias",
                "governance-context")) {
                shouldThrow<IllegalArgumentException> {
                    validateExitStatus(args.copy(options = args.options + (option to "unused")))
                }
            }
            shouldThrow<IllegalArgumentException> { validateExitStatus(args) }
            Files.exists(path) shouldBe false
            shouldThrow<IllegalArgumentException> { validateExitStatus(args.copy(databasePath = null)) }
            Files.createFile(path)
            shouldThrow<IllegalArgumentException> { validateExitStatus(args) }
            Files.size(path) shouldBe 0L
            withStore(path) { _, _ -> }
            validateExitStatus(args)
            for (field in listOf("handle", "core")) {
                shouldThrow<IllegalArgumentException> {
                    validateExitStatus(args.copy(options = args.options + (field to "")))
                }
            }
        } finally {
            Files.deleteIfExists(missing.resolve("absent.db"))
            Files.delete(missing)
        }
    }
    test("rendered progress separates current membership from historical completion and file delivery") {
        val target = ExitTarget(PersonId("alice"), CoreId("chosen"))
        val now = Instant.parse("2026-11-28T12:00:00Z")
        val past = ExitProcess("first", target, now, now, ExitStage.COMPLETED)
        val pending = ExitProcess("second", target, now, now, ExitStage.EXPORT_READY)
        val rendered = formatExitStatus(ExitStatus(target, true,
            listOf(ExitProgress(past, "past-digest"), ExitProgress(pending, "current-digest"))))
        rendered.contains("Активне членство: так") shouldBe true
        rendered.contains("Exit: first; stage: COMPLETED") shouldBe true
        rendered.contains("Exit: second; stage: EXPORT_READY") shouldBe true
        rendered.contains("SHA-256 архіву в БД: current-digest") shouldBe true
        rendered.contains("не підтверджує його доставку у файл") shouldBe true
        formatExitStatus(ExitStatus(target, false, emptyList()))
            .contains("Записів про власний вихід із цього Core немає.") shouldBe true
    }
})
