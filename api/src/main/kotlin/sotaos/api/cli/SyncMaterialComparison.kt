package sotaos.api.cli

import kotlinx.serialization.json.*
import sotaos.api.sync.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date

/** Local snapshots only: no database connection, network probe, file replacement or trust decision. */
internal fun compareSyncMaterial(input: Arguments, secrets: P09Secrets = P09Secrets(),
    at: Instant = Instant.now(), output: (String) -> Unit = ::println) {
    require(input.databasePath == null && input.options.keys == setOf("config", "candidate")) {
        "Use sync compare --config CURRENT --candidate STAGED without overrides."
    }
    val current = P09NodeConfiguration.load(Path.of(input.options.getValue("config")), "run")
    val candidate = P09NodeConfiguration.load(Path.of(input.options.getValue("candidate")), "run")
    require(current - TLS_PATHS == candidate - TLS_PATHS) {
        "TLS comparison requires identical node settings apart from keystore and truststore paths."
    }
    val database = Path.of(current.getValue("db"))
    require(Files.isRegularFile(database) && Files.size(database) > 0) { "Provision the database before sync." }
    secrets.peerToken()
    val observedAt = Date.from(at).toInstant()
    val comparison = secrets.withComparisonPasswords { currentPassword, candidatePassword ->
        val previous = inspectBundle(current, currentPassword, observedAt)
        val staged = inspectBundle(candidate, candidatePassword, observedAt)
        comparisonJson(previous, staged, observedAt)
    }
    output("P09 TLS comparison $comparison")
    output("P09 TLS comparison complete: both bundles passed local validation at the reported time. " +
        "Keep the previous bundle and recheck it before recovery; verify peer exchange after restart.")
}

private data class TlsBundle(val leaves: List<P09CertificateInfo>, val trust: List<String>)

private fun inspectBundle(settings: Map<String, String>, password: CharArray, at: Instant): TlsBundle {
    var leaves = emptyList<P09CertificateInfo>()
    var trust = emptyList<String>()
    P09Tls.serverWithDiagnostics(Path.of(settings.getValue("keystore")), password, at) { leaves = it }
    P09Tls.clientWithDiagnostics(Path.of(settings.getValue("truststore")), password) { trust = it }
    require(trust.isNotEmpty()) { "TLS comparison requires explicit X.509 client trust anchors." }
    return TlsBundle(leaves, trust)
}

private fun comparisonJson(current: TlsBundle, candidate: TlsBundle, at: Instant): JsonObject = buildJsonObject {
    put("observedAt", at.toString())
    put("current", bundleJson(current))
    put("candidate", bundleJson(candidate))
    put("serverLeaves", differences(current.leaves.map { it.sha256 }, candidate.leaves.map { it.sha256 }))
    put("clientTrust", differences(current.trust, candidate.trust))
    put("currentRecovery", "LOCAL_MATERIAL_VALID")
}

private fun bundleJson(bundle: TlsBundle): JsonObject = buildJsonObject {
    put("serverLeaves", JsonArray(bundle.leaves.map(::certificateMetadata)))
    put("clientTrust", JsonArray(bundle.trust.map(::JsonPrimitive)))
}

private fun differences(current: List<String>, candidate: List<String>): JsonObject = buildJsonObject {
    put("added", fingerprints(candidate.toSet() - current.toSet()))
    put("removed", fingerprints(current.toSet() - candidate.toSet()))
    put("retained", fingerprints(current.toSet().intersect(candidate.toSet())))
}

private fun fingerprints(values: Set<String>): JsonArray = JsonArray(values.sorted().map(::JsonPrimitive))

private val TLS_PATHS = setOf("keystore", "truststore")
