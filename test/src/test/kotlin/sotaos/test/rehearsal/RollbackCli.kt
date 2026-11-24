package sotaos.test.rehearsal

import sotaos.api.sync.P09Tls
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Secret files are confined to a disposable private directory and removed on every exit. */
internal class RollbackCli(private val directory: Path, current: RehearsalTls,
    candidate: RehearsalTls, token: String) : AutoCloseable {
    private val secrets = Files.createTempDirectory(directory, "secrets-")
    val currentPassword: Path = secrets.resolve("current.password")
    val candidatePassword: Path = secrets.resolve("candidate.password")
    private val tokenFile = secrets.resolve("peer.token")

    init {
        runCatching {
            writeSecret(currentPassword, current.password)
            writeSecret(candidatePassword, candidate.password)
            writeSecret(tokenFile, token)
        }.onFailure { close() }.getOrThrow()
    }

    fun configuration(name: String, node: RehearsalNode, peer: RehearsalNode,
        tls: RehearsalTls, port: Int): Path = directory.resolve("$name.conf").also { path ->
        Files.writeString(path, """
            version=1
            db=${node.path}
            node=${node.name}
            actor=${node.person.id.value}
            governance-context=${governance.domain}
            peer=${peer.name}
            endpoint=https://localhost:1/p09
            keystore=${tls.keyStore(node.name)}
            truststore=${tls.trustStore(peer.name)}
            bind=127.0.0.1
            port=$port
        """.trimIndent())
    }

    fun check(config: Path, password: Path, name: String, succeeds: Boolean = true) =
        run(listOf("sync", "check", "--config", config.toString()), password, name, succeeds)

    fun compare(current: Path, candidate: Path) = run(listOf("sync", "compare", "--config", current.toString(),
        "--candidate", candidate.toString()), currentPassword, "compare", true, candidatePassword)

    private fun run(arguments: List<String>, password: Path, name: String, succeeds: Boolean,
        candidate: Path? = null) {
        val log = directory.resolve("$name.log")
        val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), "sotaos.api.cli.MainKt") + arguments)
            .redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment().keys.removeIf { it.startsWith("SOTA_P09_") }
                environment()["SOTA_P09_TLS_PASSWORD_FILE"] = password.toString()
                environment()["SOTA_P09_PEER_TOKEN_FILE"] = tokenFile.toString()
                candidate?.let { environment()["SOTA_P09_CANDIDATE_TLS_PASSWORD_FILE"] = it.toString() }
            }.start()
        try {
            check(process.waitFor(CLI_SECONDS, TimeUnit.SECONDS)) { "TLS preflight timed out; inspect $log" }
            check((process.exitValue() == 0) == succeeds) { "Unexpected preflight result; inspect $log" }
            val output = Files.readString(log)
            if (succeeds) check(if (arguments[1] == "compare") "P09 TLS comparison " in output
                else "P09 local configuration and TLS/credential material are readable" in output)
            Files.list(secrets).use { files -> files.forEach {
                check(Files.readString(it).trimEnd() !in output) { "Secret found in preflight output" }
            } }
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    private fun writeSecret(path: Path, value: String) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(path, value + "\n")
    }

    override fun close() {
        Files.list(secrets).use { files -> files.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(secrets)
    }
}

internal fun rollbackClient(trust: Path, passwordFile: Path): HttpClient {
    val password = Files.readString(passwordFile).removeSuffix("\n").toCharArray()
    return try {
        HttpClient.newBuilder().sslContext(P09Tls.client(trust, password))
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    } finally { password.fill('\u0000') }
}

internal fun fileDigests(paths: List<Path>): Map<Path, String> = paths.associateWith { path ->
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}

private const val CLI_SECONDS = 45L
