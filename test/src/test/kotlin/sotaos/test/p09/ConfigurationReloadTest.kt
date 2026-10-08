package sotaos.test.p09

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.sync.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ConfigurationReloadTest : FunSpec({
    test("valid timing changes apply once and malformed or missing files preserve the accepted settings") {
        withReloadFile { path ->
            val initial = P09NodeConfiguration.load(path, "run")
            val reload = P09ConfigurationReload(path, initial)
            val accepted = mutableListOf<SyncLoopSettings>()
            reload.poll { accepted += it } shouldBe ConfigurationReloadResult.UNCHANGED
            Files.writeString(path, CONFIG.replace("\ninterval-seconds=1", "\ninterval-seconds=0"))
            reload.poll { accepted += it } shouldBe ConfigurationReloadResult.REJECTED
            accepted.size shouldBe 0
            Files.writeString(path, CONFIG.replace("\ninterval-seconds=1", "\ninterval-seconds=3"))
            reload.poll { accepted += it } shouldBe ConfigurationReloadResult.APPLIED
            accepted.single().interval shouldBe Duration.ofSeconds(3)
            reload.poll { accepted += it } shouldBe ConfigurationReloadResult.UNCHANGED
            Files.delete(path)
            reload.poll { accepted += it } shouldBe ConfigurationReloadResult.REJECTED
            accepted.size shouldBe 1
        }
    }
    test("valid fixed-field changes reject the entire candidate including otherwise valid timing changes") {
        withReloadFile { path ->
            val reload = P09ConfigurationReload(path, P09NodeConfiguration.load(path, "run"))
            val changes = mapOf("db" to "other.db", "node" to "other-node", "actor" to "other-person",
                "governance-context" to "other-context", "peer" to "other-peer",
                "endpoint" to "https://other.example/p09", "keystore" to "other.p12", "truststore" to "other.p12",
                "port" to "8444", "status-interval-seconds" to "2", "bind" to "0.0.0.0", "metrics-format" to "json")
            changes.forEach { (key, value) ->
                val candidate = CONFIG.lines().filterNot { it.startsWith("$key=") }.joinToString("\n") + "\n$key=$value"
                Files.writeString(path, candidate.replace("\ninterval-seconds=1", "\ninterval-seconds=3"))
                reload.poll { error("A fixed field must never be applied") } shouldBe ConfigurationReloadResult.REJECTED
            }
        }
    }
})

private fun withReloadFile(block: (Path) -> Unit) {
    val path = Files.createTempFile("p09-reload-", ".conf")
    try {
        Files.writeString(path, CONFIG)
        block(path)
    } finally { Files.deleteIfExists(path) }
}

private val CONFIG = """
    version=1
    db=node.db
    node=node-a
    actor=person-a
    governance-context=governance
    keystore=server.p12
    truststore=trust.p12
    port=8443
    peer=node-b
    endpoint=https://peer.example/p09
    interval-seconds=1
    max-backoff-seconds=2
    status-interval-seconds=1
    reload-config=true
""".trimIndent()
