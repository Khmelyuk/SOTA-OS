package sotaos.api.exit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import sotaos.application.ports.ExitDocument
import sotaos.application.ports.ExitTarget
import sotaos.domain.shared.CoreId
import sotaos.domain.shared.PersonId
import java.nio.file.Files
import java.security.MessageDigest

class ExitArchiveFileTest : FunSpec({
    test("verification streams multiple buffers and compares UTF-8 bytes without normalizing text") {
        val text = "{\"дані\":\"${"Ї😀".repeat(5000)}\"}"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val document = ExitDocument("exit", ExitTarget(PersonId("person"), CoreId("core")), text, sha)
        val path = Files.createTempFile("archive-multibuffer-", ".json")
        try {
            Files.write(path, bytes)
            verifyExitDocument(document, path)
            bytes[bytes.lastIndex] = 32
            Files.write(path, bytes)
            shouldThrow<IllegalArgumentException> { verifyExitDocument(document, path) }
            Files.write(path, text.toByteArray(Charsets.UTF_16))
            shouldThrow<IllegalArgumentException> { verifyExitDocument(document, path) }
        } finally { Files.delete(path) }
    }
})
