package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.api.sync.P09Secrets
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore

class P09ComparisonPasswordTest : FunSpec({
    val fixture by lazy { PasswordComparisonFixture() }
    beforeSpec { fixture }
    afterSpec { fixture.close() }

    test("independent direct and file passwords compare reencrypted bundles without certificate changes or writes") {
        val f = fixture
        val passwordFile = f.base.directory.resolve("candidate.password")
        Files.writeString(passwordFile, String(f.password) + "\r\n")
        for (source in listOf(mapOf(CANDIDATE_PASSWORD to String(f.password)),
            mapOf("${CANDIDATE_PASSWORD}_FILE" to passwordFile.toString()))) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
                val input = f.input(socket.localPort)
                val before = f.base.snapshot()
                val output = mutableListOf<String>()
                compareSyncMaterial(input, f.secrets(source)) { output += it }
                val report = Json.parseToJsonElement(output.first().removePrefix("P09 TLS comparison ")).jsonObject
                for (field in listOf("serverLeaves", "clientTrust")) {
                    report[field]!!.jsonObject["added"]!!.jsonArray.isEmpty() shouldBe true
                    report[field]!!.jsonObject["removed"]!!.jsonArray.isEmpty() shouldBe true
                    report[field]!!.jsonObject["retained"]!!.jsonArray.size shouldBe 1
                }
                output.size shouldBe 2
                output.any { String(f.password) in it || String(f.base.password) in it ||
                    "fixture-token" in it } shouldBe false
                f.base.snapshot() shouldBe before
            }
        }
    }

    test("absent wrong or swapped passwords fail without a successful report or file mutation") {
        val f = fixture
        val input = f.input()
        val before = f.base.snapshot()
        val cases = listOf(f.secrets(emptyMap()), f.secrets(mapOf(CANDIDATE_PASSWORD to "incorrect-fixture")),
            P09Secrets(mapOf(CURRENT_PASSWORD to String(f.password), CANDIDATE_PASSWORD to String(f.base.password),
                "SOTA_P09_PEER_TOKEN" to "fixture-token")))
        for (secrets in cases) {
            val output = mutableListOf<String>()
            shouldThrow<Exception> { compareSyncMaterial(input, secrets) { output += it } }
            output.isEmpty() shouldBe true
            f.base.snapshot() shouldBe before
        }
    }

    test("candidate password covers both stores and malformed sources cannot silently use the current password") {
        val f = fixture
        val input = f.input()
        val candidate = Path.of(input.options.getValue("candidate"))
        Files.writeString(candidate, Files.readString(candidate)
            .replace("truststore=candidate-trust.p12", "truststore=${f.base.trustOnly.fileName}"))
        shouldThrow<Exception> {
            compareSyncMaterial(input, f.secrets(mapOf(CANDIDATE_PASSWORD to String(f.password)))) {
                error("No report")
            }
        }
        val current = Path.of(input.options.getValue("config"))
        val unchanged = comparisonInput(current, current)
        for (source in listOf(mapOf(CANDIDATE_PASSWORD to ""),
            mapOf(CANDIDATE_PASSWORD to String(f.base.password), "${CANDIDATE_PASSWORD}_FILE" to "missing"))) {
            shouldThrow<IllegalArgumentException> {
                compareSyncMaterial(unchanged, f.secrets(source)) { error("No fallback") }
            }
        }
    }
})

private class PasswordComparisonFixture : AutoCloseable {
    val base = CertificateFixture()
    val password = "independent-candidate-fixture".toCharArray()
    private val server = base.directory.resolve("candidate-server.p12")
    private val trust = base.directory.resolve("candidate-trust.p12")

    init {
        reencrypt(base.server, server)
        reencrypt(base.trustOnly, trust)
    }

    fun input(port: Int = 1): Arguments {
        val current = base.directory.resolve("current.conf")
        Files.copy(comparisonConfig(base, port = port), current, REPLACE_EXISTING)
        val candidate = comparisonConfig(base, key = server, trust = trust, port = port)
        return comparisonInput(current, candidate)
    }

    fun secrets(candidate: Map<String, String>) = P09Secrets(mapOf(CURRENT_PASSWORD to String(base.password),
        "SOTA_P09_PEER_TOKEN" to "fixture-token") + candidate)

    private fun reencrypt(source: Path, target: Path) {
        val original = KeyStore.getInstance("PKCS12")
        Files.newInputStream(source).use { original.load(it, base.password) }
        val copy = KeyStore.getInstance("PKCS12").also { it.load(null, password) }
        original.aliases().toList().forEach { alias ->
            if (original.isKeyEntry(alias)) {
                copy.setKeyEntry(alias, original.getKey(alias, base.password), password,
                    original.getCertificateChain(alias))
            } else { copy.setCertificateEntry(alias, original.getCertificate(alias)) }
        }
        Files.newOutputStream(target).use { copy.store(it, password) }
    }

    override fun close() { password.fill('\u0000'); base.close() }
}
