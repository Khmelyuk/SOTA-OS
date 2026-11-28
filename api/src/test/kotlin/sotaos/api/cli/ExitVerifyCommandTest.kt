package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ExitVerifyCommandTest : FunSpec({
    test("verification requires explicit existing database and own exact exit without signing or Person override") {
        val directory = Files.createTempDirectory("exit-verify-args-")
        val path = directory.resolve("node.db")
        try {
            val options = mapOf("handle" to "alice", "core" to "chosen", "id" to "exit-id",
                "file" to directory.resolve("copy.json").toString())
            val args = Arguments("exit", "verify", path, options)
            shouldThrow<IllegalArgumentException> { validateExitVerify(args.copy(databasePath = null)) }
            shouldThrow<IllegalArgumentException> { validateExitVerify(args) }
            Files.exists(path) shouldBe false
            Files.createFile(path)
            shouldThrow<IllegalArgumentException> { validateExitVerify(args) }
            Files.size(path) shouldBe 0L
            withStore(path) { _, _ -> }
            validateExitVerify(args)
            for (name in listOf("handle", "core", "id", "file")) {
                shouldThrow<IllegalArgumentException> {
                    validateExitVerify(args.copy(options = options + (name to "")))
                }
            }
            for (name in listOf("out", "person", "node", "signing-keystore", "signing-alias", "governance-context")) {
                shouldThrow<IllegalArgumentException> {
                    validateExitVerify(args.copy(options = options + (name to "forbidden")))
                }
            }
            Files.exists(directory.resolve("copy.json")) shouldBe false
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
})
