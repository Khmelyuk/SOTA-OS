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
import java.security.KeyStore
import java.security.cert.CertificateException
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class P09MaterialComparisonTest : FunSpec({
    val current by lazy { CertificateFixture() }
    val staged by lazy { CertificateFixture(validityDays = 1) }
    beforeSpec { current; staged }
    afterSpec { current.close(); staged.close() }

    test("unchanged bundles compare read only with an occupied port and escaped public metadata") {
        val c = current
        val lines = mutableListOf<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
            val config = comparisonConfig(c, port = socket.localPort)
            val input = comparisonInput(config, config)
            val before = c.snapshot()
            compareSyncMaterial(input, c.secrets, Instant.now()) { lines += it }
            c.snapshot() shouldBe before
            socket.isClosed shouldBe false
        }
        val report = comparisonReport(lines)
        val fingerprint = CertificateFixture.digest(c.certificate.encoded)
        for (field in listOf("serverLeaves", "clientTrust")) {
            report[field]!!.jsonObject["retained"] shouldBe JsonArray(listOf(JsonPrimitive(fingerprint)))
            report[field]!!.jsonObject["added"] shouldBe JsonArray(emptyList())
            report[field]!!.jsonObject["removed"] shouldBe JsonArray(emptyList())
        }
        val leaves = report["current"]!!.jsonObject["serverLeaves"]!!.jsonArray
        leaves.size shouldBe 2
        leaves.first().jsonObject["alias"]!!.jsonPrimitive.content shouldBe c.unusualAlias
        report["currentRecovery"]!!.jsonPrimitive.content shouldBe "LOCAL_MATERIAL_VALID"
        lines.any { '\n' in it || '\u001b' in it || String(c.password) in it ||
            "public-fixture-peer-token" in it } shouldBe false
    }

    test("replacement leaf and overlapping client trust report exact added removed and retained fingerprints") {
        val c = current
        val s = staged
        val overlap = s.directory.resolve("overlap.p12")
        writeComparisonTrust(overlap, c, s)
        val first = comparisonConfig(c)
        val second = comparisonConfig(s, database = c.database, trust = overlap)
        val before = c.snapshot() to s.snapshot()
        val lines = mutableListOf<String>()
        compareSyncMaterial(comparisonInput(first, second), c.secrets) { lines += it }
        val report = comparisonReport(lines)
        val old = CertificateFixture.digest(c.certificate.encoded)
        val fresh = CertificateFixture.digest(s.certificate.encoded)
        report["serverLeaves"]!!.jsonObject shouldBe delta(listOf(fresh), listOf(old), emptyList())
        report["clientTrust"]!!.jsonObject shouldBe delta(listOf(fresh), emptyList(), listOf(old))
        val candidate = report["candidate"]!!.jsonObject["serverLeaves"]!!.jsonArray
        candidate.all { it.jsonObject["state"]!!.jsonPrimitive.content == "RENEW_SOON" } shouldBe true
        (c.snapshot() to s.snapshot()) shouldBe before
    }

    test("trust removal is visible and does not become a claim of peer acceptance") {
        val c = current
        val s = staged
        val lines = mutableListOf<String>()
        compareSyncMaterial(comparisonInput(comparisonConfig(c), comparisonConfig(s, database = c.database)),
            c.secrets) { lines += it }
        comparisonReport(lines)["clientTrust"]!!.jsonObject shouldBe delta(
            listOf(CertificateFixture.digest(s.certificate.encoded)),
            listOf(CertificateFixture.digest(c.certificate.encoded)), emptyList())
        lines.last().contains("verify peer exchange after restart") shouldBe true
    }

    test("configuration drift and command overrides fail before any comparison output") {
        val c = current
        val first = comparisonConfig(c)
        for (override in listOf("node=another", "actor=another", "peer=another", "db=another.db",
            "governance-context=another", "endpoint=https://another.invalid/p09", "interval-seconds=5")) {
            val candidate = c.directory.resolve("drift.conf")
            val key = override.substringBefore('=')
            Files.writeString(candidate, Files.readString(first).lineSequence()
                .filterNot { it.startsWith("$key=") }.joinToString("\n") + "\n$override")
            shouldThrow<IllegalArgumentException> {
                compareSyncMaterial(comparisonInput(first, candidate), c.secrets) { error("Unexpected output") }
            }.message shouldBe
                "TLS comparison requires identical node settings apart from keystore and truststore paths."
        }
        val input = comparisonInput(first, first)
        for (invalid in listOf(input.copy(databasePath = c.database), input.copy(options = input.options - "candidate"),
            input.copy(options = input.options + ("node" to "override")))) {
            shouldThrow<IllegalArgumentException> {
                compareSyncMaterial(invalid, c.secrets) { error("Unexpected output") }
            }
        }
    }

    test("invalid candidate and expired recovery material cannot produce a successful comparison") {
        val c = current
        val s = staged
        val input = comparisonInput(comparisonConfig(c), comparisonConfig(s, database = c.database))
        val before = c.snapshot() to s.snapshot()
        val observations = listOf(c.certificate.notBefore.toInstant().minusMillis(1),
            s.certificate.notAfter.toInstant().plusMillis(1), c.certificate.notAfter.toInstant().plusMillis(1))
        for (at in observations) {
            shouldThrow<CertificateException> {
                compareSyncMaterial(input, c.secrets, at) { error("Unexpected output") }
            }
        }
        (c.snapshot() to s.snapshot()) shouldBe before
    }

    test("wrong password missing key empty trust and non certificate trust cannot pass comparison") {
        val c = current
        val s = staged
        val first = comparisonConfig(c)
        val secretOnly = s.directory.resolve("secret-only.p12")
        val store = KeyStore.getInstance("PKCS12").also { it.load(null, s.password) }
        store.setKeyEntry("symmetric", SecretKeySpec(ByteArray(16), "AES"), s.password, null)
        Files.newOutputStream(secretOnly).use { store.store(it, s.password) }
        for ((key, trust) in listOf(s.trustOnly to s.trustOnly, s.server to s.empty, s.server to secretOnly)) {
            val input = comparisonInput(first, comparisonConfig(s, database = c.database, key = key, trust = trust))
            shouldThrow<IllegalArgumentException> {
                compareSyncMaterial(input, c.secrets) { error("Unexpected output") }
            }
        }
        val wrong = P09Secrets(mapOf("SOTA_P09_TLS_PASSWORD" to "incorrect", "SOTA_P09_PEER_TOKEN" to "fixture"))
        shouldThrow<Exception> {
            compareSyncMaterial(comparisonInput(first, first), wrong) { error("Unexpected output") }
        }
    }
})

private fun comparisonConfig(fixture: CertificateFixture, database: Path = fixture.database,
    key: Path = fixture.server, trust: Path = fixture.trustOnly, port: Int = 1): Path {
    val path = fixture.directory.resolve("compare.conf")
    Files.writeString(path, """
        version=1
        db=$database
        node=fixture-node
        actor=fixture-person
        governance-context=fixture-context
        peer=fixture-peer
        endpoint=https://localhost:1/p09
        port=$port
        keystore=${key.fileName}
        truststore=${trust.fileName}
    """.trimIndent())
    return path
}

private fun comparisonInput(current: Path, candidate: Path) = Arguments("sync", "compare", null,
    mapOf("config" to current.toString(), "candidate" to candidate.toString()))

private fun comparisonReport(lines: List<String>): JsonObject {
    lines.size shouldBe 2
    return Json.parseToJsonElement(lines.first().removePrefix("P09 TLS comparison ")).jsonObject
}

private fun delta(added: List<String>, removed: List<String>, retained: List<String>) = buildJsonObject {
    put("added", JsonArray(added.sorted().map(::JsonPrimitive)))
    put("removed", JsonArray(removed.sorted().map(::JsonPrimitive)))
    put("retained", JsonArray(retained.sorted().map(::JsonPrimitive)))
}

private fun writeComparisonTrust(path: Path, current: CertificateFixture, staged: CertificateFixture) {
    val store = KeyStore.getInstance("PKCS12").also { it.load(null, current.password) }
    store.setCertificateEntry("old", current.certificate)
    store.setCertificateEntry("new", staged.certificate)
    store.setCertificateEntry("duplicate", staged.certificate)
    Files.newOutputStream(path).use { store.store(it, current.password) }
}
