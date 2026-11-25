package sotaos.test.rehearsal

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

internal data class RolloutBundle(val tls: RehearsalTls, val trust: Path, val password: Path)

/** Public overlap anchors are written under each generation's password; key stores stay untouched. */
internal class RolloutMaterial(directory: Path, private val current: RehearsalTls,
    private val candidate: RehearsalTls, private val cli: RollbackCli) : AutoCloseable {
    private val folder = Files.createTempDirectory(directory, "overlap-")

    init {
        runCatching {
            listOf("a", "b").forEach { peer ->
                writeOverlap(peer, "current", current.password)
                writeOverlap(peer, "candidate", candidate.password)
            }
        }.onFailure { close() }.getOrThrow()
    }

    fun bundle(node: RehearsalNode, newKey: Boolean, overlap: Boolean): RolloutBundle {
        val peer = if (node.name == "a") "b" else "a"
        val tls = if (newKey) candidate else current
        val generation = if (newKey) "candidate" else "current"
        return RolloutBundle(tls, if (overlap) folder.resolve("$generation-$peer.p12") else tls.trustStore(peer),
            if (newKey) cli.candidatePassword else cli.currentPassword)
    }

    fun files(): List<Path> = Files.list(folder).use { it.toList() } +
        listOf(cli.currentPassword, cli.candidatePassword) + listOf(current, candidate).flatMap { tls ->
            listOf("a", "b").flatMap { listOf(tls.keyStore(it), tls.trustStore(it)) }
        }

    private fun writeOverlap(peer: String, name: String, value: String) {
        val password = value.toCharArray()
        try {
            val trust = KeyStore.getInstance("PKCS12").also { it.load(null, password) }
            listOf(current, candidate).forEachIndexed { index, tls ->
                val sourcePassword = tls.password.toCharArray()
                try {
                    val source = KeyStore.getInstance("PKCS12")
                    Files.newInputStream(tls.trustStore(peer)).use { source.load(it, sourcePassword) }
                    trust.setCertificateEntry("generation-$index", source.getCertificate("peer"))
                } finally { sourcePassword.fill('\u0000') }
            }
            check(trust.getCertificate("generation-0") != trust.getCertificate("generation-1"))
            Files.newOutputStream(folder.resolve("$name-$peer.p12")).use { trust.store(it, password) }
        } finally { password.fill('\u0000') }
    }

    override fun close() {
        Files.list(folder).use { files -> files.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(folder)
    }
}
