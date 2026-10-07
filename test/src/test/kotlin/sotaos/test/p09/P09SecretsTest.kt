package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09Secrets
import java.nio.file.Files

class P09SecretsTest : FunSpec({
    test("secret source must be explicit and unambiguous without fallback") {
        shouldThrow<IllegalArgumentException> { P09Secrets(emptyMap()).peerToken() }
        shouldThrow<IllegalArgumentException> {
            P09Secrets(mapOf("SOTA_P09_PEER_TOKEN" to "token", "SOTA_P09_PEER_TOKEN_FILE" to "missing")).peerToken()
        }
        P09Secrets(mapOf("SOTA_P09_PEER_TOKEN" to "valid-token")).peerToken() shouldBe "valid-token"
    }
    test("file source rereads replacements and rejects missing or malformed material instead of caching a token") {
        val file = Files.createTempFile("p09-secret-", ".txt")
        val source = P09Secrets(mapOf("SOTA_P09_PEER_TOKEN_FILE" to file.toString()))
        try {
            Files.writeString(file, "first-token\r\n")
            source.peerToken() shouldBe "first-token"
            Files.writeString(file, "second-token\n")
            source.peerToken() shouldBe "second-token"
            listOf("", "token with space", "token\nextra", "token\r", "x".repeat(OVERSIZED_SECRET)).forEach {
                Files.writeString(file, it)
                shouldThrow<IllegalArgumentException> { source.peerToken() }
            }
            Files.write(file, byteArrayOf(-61, 40))
            shouldThrow<IllegalArgumentException> { source.peerToken() }
            Files.delete(file)
            shouldThrow<IllegalArgumentException> { source.peerToken() }
        } finally { Files.deleteIfExists(file) }
    }
    test("TLS password file preserves spaces and removes only its terminal newline") {
        val file = Files.createTempFile("p09-password-", ".txt")
        try {
            Files.writeString(file, " password with spaces \n")
            val password = P09Secrets(mapOf("SOTA_P09_TLS_PASSWORD_FILE" to file.toString())).tlsPassword()
            String(password) shouldBe " password with spaces "
            password.fill('\u0000')
        } finally { Files.deleteIfExists(file) }
    }
})

private const val OVERSIZED_SECRET = 4097
