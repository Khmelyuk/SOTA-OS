package sotaos.test.p09

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.P09NodeConfiguration
import java.nio.file.Files
import java.nio.file.Path

class NodeConfigurationTest : FunSpec({
    test("configuration resolves UTF8 paths relative to the file and preserves values containing equals") {
        withConfiguration("# node settings\n$RUN_CONFIG\nstatus-interval-seconds=5\n") { path ->
            val values = P09NodeConfiguration.load(path, "run")
            values["db"] shouldBe path.parent.resolve("дані/node.db").toString()
            values["endpoint"] shouldBe "https://peer.example/p09?profile=a=b"
            values["status-interval-seconds"] shouldBe "5"
            values.containsKey("version") shouldBe false
        }
    }
    test("unknown secret and duplicate fields fail without disclosing their values") {
        listOf("token=secret-token", "node=secret-token", "tls-password=secret-token").forEach { extra ->
            withConfiguration("$RUN_CONFIG\n$extra") { path ->
                val failure = shouldThrow<IllegalArgumentException> { P09NodeConfiguration.load(path, "run") }
                failure.message.orEmpty().contains("secret-token") shouldBe false
            }
        }
    }
    test("missing fields unsupported versions and malformed lines are rejected") {
        listOf(RUN_CONFIG.replace("version=1", "version=2"), RUN_CONFIG.replace("node=local", "node="),
            RUN_CONFIG.replace("actor=person", "# absent actor"), "$RUN_CONFIG\nmalformed").forEach { text ->
            withConfiguration(text) { path ->
                shouldThrow<IllegalArgumentException> { P09NodeConfiguration.load(path, "run") }
            }
        }
    }
    test("invalid timers ports self peers and unsafe endpoints fail before hosting") {
        listOf("port=0", "port=65536", "interval-seconds=0", "max-backoff-seconds=3601",
            "status-interval-seconds=invalid", "peer=local", "endpoint=http://peer.example/p09",
            "endpoint=https://user:secret@peer.example/p09", "endpoint=https://peer.example/p09#fragment")
            .forEach { replacement ->
                val key = replacement.substringBefore('=')
                val text = RUN_CONFIG.lines().filterNot { it.startsWith("$key=") }.joinToString("\n")
                withConfiguration("$text\n$replacement") { path ->
                    shouldThrow<IllegalArgumentException> { P09NodeConfiguration.load(path, "run") }
                }
            }
    }
    test("mode-specific configurations accept only options used by that mode") {
        val serve = RUN_CONFIG.lines().filterNot {
            it.startsWith("peer=") || it.startsWith("endpoint=") || it.startsWith("truststore=")
        }.joinToString("\n")
        withConfiguration(serve) { path -> P09NodeConfiguration.load(path, "serve")["port"] shouldBe "8443" }
        val once = RUN_CONFIG.lines().filterNot { it.startsWith("keystore=") || it.startsWith("port=") }
            .joinToString("\n")
        withConfiguration(once) { path -> P09NodeConfiguration.load(path, "once")["peer"] shouldBe "remote" }
        withConfiguration(RUN_CONFIG) { path ->
            shouldThrow<IllegalArgumentException> { P09NodeConfiguration.load(path, "serve") }
        }
    }
    test("oversized and malformed UTF8 configurations are rejected") {
        withConfiguration("x".repeat(OVERSIZED_BYTES)) { path ->
            shouldThrow<IllegalArgumentException> { P09NodeConfiguration.load(path, "run") }
            Files.write(path, byteArrayOf(-61, 40))
            shouldThrow<java.nio.charset.CharacterCodingException> { P09NodeConfiguration.load(path, "run") }
        }
    }
})

private fun withConfiguration(text: String, block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("p09-config-")
    val path = directory.resolve("node.conf")
    try {
        Files.writeString(path, text)
        block(path)
    } finally {
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }
}

private const val OVERSIZED_BYTES = 65_537
private val RUN_CONFIG = """
    version=1
    db=дані/node.db
    node=local
    actor=person
    governance-context=peer-governance
    keystore=keys/server.p12
    port=8443
    peer=remote
    endpoint=https://peer.example/p09?profile=a=b
    truststore=keys/trust.p12
""".trimIndent()
