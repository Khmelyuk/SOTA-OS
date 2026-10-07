package sotaos.api.cli

import sotaos.api.sync.P09Secrets
import sotaos.api.sync.P09Tls
import java.nio.file.Files
import java.nio.file.Path

/** Read-only local material check: no listener, SQLite connection, peer request or trust mutation. */
internal fun checkSyncMaterial(arguments: Arguments) {
    val database = requireNotNull(arguments.databasePath)
    require(Files.isRegularFile(database) && Files.size(database) > 0) { "Provision the database before sync." }
    val secrets = P09Secrets()
    secrets.peerToken()
    val password = secrets.tlsPassword()
    try {
        P09Tls.server(Path.of(arguments.options.getValue("keystore")), password)
        P09Tls.client(Path.of(arguments.options.getValue("truststore")), password)
    } finally { password.fill('\u0000') }
    println("P09 local configuration and TLS/credential material are readable; no peer exchange performed.")
}
