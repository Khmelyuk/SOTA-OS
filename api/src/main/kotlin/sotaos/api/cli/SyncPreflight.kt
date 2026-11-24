package sotaos.api.cli

import kotlinx.serialization.json.*
import sotaos.api.sync.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Read-only local material check: no listener, SQLite connection, peer request or trust mutation. */
internal fun checkSyncMaterial(arguments: Arguments, secrets: P09Secrets = P09Secrets(),
    at: Instant = Instant.now(), output: (String) -> Unit = ::println) {
    val database = requireNotNull(arguments.databasePath)
    require(Files.isRegularFile(database) && Files.size(database) > 0) { "Provision the database before sync." }
    secrets.peerToken()
    val password = secrets.tlsPassword()
    var renewalNeeded = false
    try {
        P09Tls.serverWithDiagnostics(Path.of(arguments.options.getValue("keystore")), password, at) { certificates ->
            renewalNeeded = certificates.any { it.state == P09CertificateState.RENEW_SOON }
            certificates.forEach { output("P09 TLS certificate ${certificateJson(it)}") }
        }
        P09Tls.client(Path.of(arguments.options.getValue("truststore")), password)
    } finally { password.fill('\u0000') }
    if (renewalNeeded) {
        output("P09 TLS renewal: certificate expires within $RENEWAL_WARNING_DAYS days; " +
            "stage replacement material and run sync check before restarting the node.")
    }
    output("P09 local configuration and TLS/credential material are readable; no peer exchange performed.")
}

internal fun certificateJson(certificate: P09CertificateInfo): String = certificateMetadata(certificate).toString()

internal fun certificateMetadata(certificate: P09CertificateInfo): JsonObject = buildJsonObject {
    put("alias", certificate.alias)
    put("sha256", certificate.sha256)
    put("notBefore", certificate.notBefore.toString())
    put("notAfter", certificate.notAfter.toString())
    put("observedAt", certificate.observedAt.toString())
    put("secondsRemaining", certificate.secondsRemaining)
    put("state", certificate.state.name)
}
