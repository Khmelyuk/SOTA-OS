package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ExitArchiveCommandTest : FunSpec({
    test("archive requires explicit existing database and own exact exit without signing or Person override") {
        val directory = Files.createTempDirectory("exit-archive-args-")
        val path = directory.resolve("node.db")
        try {
            val options = mapOf("handle" to "alice", "core" to "chosen", "id" to "exit-id",
                "out" to directory.resolve("copy.json").toString())
            val args = Arguments("exit", "archive", path, options)
            shouldThrow<IllegalArgumentException> { validateExitArchive(args.copy(databasePath = null)) }
            shouldThrow<IllegalArgumentException> { validateExitArchive(args) }
            Files.exists(path) shouldBe false
            Files.createFile(path)
            shouldThrow<IllegalArgumentException> { validateExitArchive(args) }
            Files.size(path) shouldBe 0L
            withStore(path) { _, _ -> }
            validateExitArchive(args)
            for (name in listOf("handle", "core", "id", "out")) {
                shouldThrow<IllegalArgumentException> {
                    validateExitArchive(args.copy(options = options + (name to "")))
                }
            }
            for (name in listOf("person", "node", "signing-keystore", "signing-alias", "governance-context")) {
                shouldThrow<IllegalArgumentException> {
                    validateExitArchive(args.copy(options = options + (name to "forbidden")))
                }
            }
            Files.exists(directory.resolve("copy.json")) shouldBe false
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
})
