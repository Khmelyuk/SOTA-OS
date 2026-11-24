package sotaos.api.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import sotaos.api.sync.*
import java.net.InetAddress
import java.net.ServerSocket
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.time.Duration
import java.util.Date

class P09CertificatePreflightTest : FunSpec({
    val fixture by lazy { CertificateFixture() }
    beforeSpec { fixture }
    afterSpec { fixture.close() }

    test("diagnostics inventory all key leaves deterministically with fingerprints from the loaded certificates") {
        val f = fixture
        val at = f.certificate.notBefore.toInstant().plusSeconds(1)
        var reports = emptyList<P09CertificateInfo>()
        P09Tls.serverWithDiagnostics(f.server, f.password, at) { reports = it }
        reports.map { it.alias } shouldBe listOf(f.unusualAlias, "server")
        reports.forEach {
            it.sha256 shouldBe CertificateFixture.digest(f.certificate.encoded)
            it.notBefore shouldBe f.certificate.notBefore.toInstant()
            it.notAfter shouldBe f.certificate.notAfter.toInstant()
            it.observedAt shouldBe at
            it.secondsRemaining shouldBe Duration.between(at, it.notAfter).seconds
            it.state shouldBe P09CertificateState.VALID
        }
    }

    test("renewal threshold and inclusive certificate validity boundaries use the same millisecond observation") {
        val f = fixture
        val start = f.certificate.notBefore.toInstant()
        val end = f.certificate.notAfter.toInstant()
        val threshold = end.minus(Duration.ofDays(RENEWAL_WARNING_DAYS))
        val cases = listOf(start.minusMillis(1) to P09CertificateState.NOT_YET_VALID,
            start to P09CertificateState.VALID, threshold.minusMillis(1) to P09CertificateState.VALID,
            threshold to P09CertificateState.RENEW_SOON, end to P09CertificateState.RENEW_SOON,
            end.plusNanos(1) to P09CertificateState.RENEW_SOON, end.plusMillis(1) to P09CertificateState.EXPIRED)
        for ((at, expected) in cases) {
            var reports = emptyList<P09CertificateInfo>()
            val result = runCatching { P09Tls.serverWithDiagnostics(f.server, f.password, at) { reports = it } }
            reports.size shouldBe 2
            reports.forEach { it.state shouldBe expected; it.observedAt shouldBe Date.from(at).toInstant() }
            when (expected) {
                P09CertificateState.EXPIRED -> (result.exceptionOrNull() is CertificateExpiredException) shouldBe true
                P09CertificateState.NOT_YET_VALID ->
                    (result.exceptionOrNull() is CertificateNotYetValidException) shouldBe true
                else -> result.isSuccess shouldBe true
            }
        }
    }

    test("wrong password and missing private key cannot report successful certificate material") {
        val f = fixture
        var reported = false
        shouldThrow<Exception> {
            P09Tls.serverWithDiagnostics(f.server, "incorrect".toCharArray()) { reported = true }
        }
        shouldThrow<IllegalArgumentException> {
            P09Tls.serverWithDiagnostics(f.trustOnly, f.password) { reported = true }
        }
        reported shouldBe false
    }

    test("near expiry check remains read only with occupied port and escapes alias metadata without exposing secrets") {
        val f = fixture
        val before = f.snapshot()
        val output = mutableListOf<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { occupied ->
            checkSyncMaterial(f.arguments(occupied.localPort), f.secrets,
                f.certificate.notAfter.toInstant().minusSeconds(1)) { output += it }
            occupied.isClosed shouldBe false
        }
        val rows = output.filter { it.startsWith("P09 TLS certificate ") }
        rows.size shouldBe 2
        rows.all { '\n' !in it && '\u001b' !in it } shouldBe true
        val first = Json.parseToJsonElement(rows.first().removePrefix("P09 TLS certificate ")).jsonObject
        first.getValue("alias").jsonPrimitive.content shouldBe f.unusualAlias
        first.getValue("state").jsonPrimitive.content shouldBe "RENEW_SOON"
        first.getValue("secondsRemaining").jsonPrimitive.long shouldBe 1
        output.count { it.startsWith("P09 TLS renewal:") } shouldBe 1
        output.last().startsWith("P09 local configuration") shouldBe true
        output.any { it.contains(String(f.password)) || it.contains("public-fixture-peer-token") } shouldBe false
        f.snapshot() shouldBe before
    }

    test("expired and not yet valid checks report their cause and still fail without a success summary or writes") {
        val f = fixture
        val before = f.snapshot()
        val cases = listOf(f.certificate.notBefore.toInstant().minusMillis(1) to "NOT_YET_VALID",
            f.certificate.notAfter.toInstant().plusMillis(1) to "EXPIRED")
        for ((at, state) in cases) {
            val output = mutableListOf<String>()
            shouldThrow<java.security.cert.CertificateException> {
                checkSyncMaterial(f.arguments(), f.secrets, at) { output += it }
            }
            output.size shouldBe 2
            output.all { it.contains("\"state\":\"$state\"") } shouldBe true
            f.snapshot() shouldBe before
        }
    }

    test("valid leaf diagnostics do not claim preflight success when explicit client trust is empty") {
        val f = fixture
        val before = f.snapshot()
        val output = mutableListOf<String>()
        shouldThrow<IllegalArgumentException> {
            checkSyncMaterial(f.arguments(trust = f.empty), f.secrets,
                f.certificate.notAfter.toInstant().minusSeconds(1)) { output += it }
        }
        output.size shouldBe 2
        output.all { it.startsWith("P09 TLS certificate ") } shouldBe true
        f.snapshot() shouldBe before
    }
})
