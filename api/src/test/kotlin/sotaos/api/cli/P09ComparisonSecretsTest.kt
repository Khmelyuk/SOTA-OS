package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09Secrets
import java.nio.file.Files

class P09ComparisonSecretsTest : FunSpec({
    test("shared and independent passwords use separate cleared buffers and preserve spaces") {
        for (candidate in listOf(null, " candidate fixture password ")) {
            val environment = mapOf(CURRENT_PASSWORD to " current fixture password ") +
                (candidate?.let { mapOf(CANDIDATE_PASSWORD to it) } ?: emptyMap())
            var buffers = emptyList<CharArray>()
            P09Secrets(environment).withComparisonPasswords { current, staged ->
                buffers = listOf(current, staged)
                (current === staged) shouldBe false
                String(current) shouldBe " current fixture password "
                String(staged) shouldBe (candidate ?: " current fixture password ")
                "completed"
            } shouldBe "completed"
            buffers.all { chars -> chars.all { it == '\u0000' } } shouldBe true
        }
    }

    test("both password buffers are cleared when material validation throws") {
        var buffers = emptyList<CharArray>()
        shouldThrow<IllegalStateException> {
            P09Secrets(mapOf(CURRENT_PASSWORD to "current-fixture", CANDIDATE_PASSWORD to "candidate-fixture"))
                .withComparisonPasswords { current, candidate ->
                    buffers = listOf(current, candidate)
                    error("validation failed")
                }
        }
        buffers.size shouldBe 2
        buffers.all { chars -> chars.all { it == '\u0000' } } shouldBe true
    }

    test("explicit invalid candidate sources never fall back and do not change ordinary password selection") {
        val sources = listOf(mapOf(CANDIDATE_PASSWORD to ""), mapOf(CANDIDATE_PASSWORD to "line\nbreak"),
            mapOf(CANDIDATE_PASSWORD to "nul\u0000value"), mapOf("${CANDIDATE_PASSWORD}_FILE" to ""),
            mapOf(CANDIDATE_PASSWORD to "candidate-fixture", "${CANDIDATE_PASSWORD}_FILE" to "missing"))
        for (source in sources) {
            val secrets = P09Secrets(mapOf(CURRENT_PASSWORD to "current-fixture") + source)
            shouldThrow<IllegalArgumentException> {
                secrets.withComparisonPasswords { _, _ -> error("Must not reach material validation") }
            }
            val ordinary = secrets.tlsPassword()
            try { String(ordinary) shouldBe "current-fixture" } finally { ordinary.fill('\u0000') }
        }
        shouldThrow<IllegalArgumentException> {
            P09Secrets(mapOf(CANDIDATE_PASSWORD to "candidate-fixture"))
                .withComparisonPasswords { _, _ -> error("Current source is mandatory") }
        }
    }

    test("candidate file sources reread values and enforce bounded UTF8 single line input") {
        val file = Files.createTempFile("sota-candidate-password-", ".txt")
        val secrets = P09Secrets(mapOf(CURRENT_PASSWORD to "current-fixture",
            "${CANDIDATE_PASSWORD}_FILE" to file.toString()))
        try {
            for (ending in listOf("\n", "\r\n")) {
                Files.writeString(file, " candidate fixture $ending")
                secrets.withComparisonPasswords { _, candidate -> String(candidate) shouldBe " candidate fixture " }
            }
            for (value in listOf("", "line\nextra", "bare\r", "x".repeat(OVERSIZED_SECRET))) {
                Files.writeString(file, value)
                shouldThrow<IllegalArgumentException> {
                    secrets.withComparisonPasswords { _, _ -> error("Invalid file") }
                }
            }
            Files.write(file, byteArrayOf(-61, 40))
            shouldThrow<IllegalArgumentException> { secrets.withComparisonPasswords { _, _ -> error("Invalid UTF8") } }
            Files.delete(file)
            shouldThrow<IllegalArgumentException> { secrets.withComparisonPasswords { _, _ -> error("Missing file") } }
        } finally { Files.deleteIfExists(file) }
    }
})

internal const val CURRENT_PASSWORD = "SOTA_P09_TLS_PASSWORD"
internal const val CANDIDATE_PASSWORD = "SOTA_P09_CANDIDATE_TLS_PASSWORD"
private const val OVERSIZED_SECRET = 4097
