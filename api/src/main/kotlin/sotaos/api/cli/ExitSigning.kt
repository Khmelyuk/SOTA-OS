package sotaos.api.cli

import sotaos.api.exit.P10Runtime
import sotaos.api.exit.P10SigningKeys
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.domain.shared.Context
import sotaos.domain.shared.SotaId
import sotaos.domain.shared.SubjectRef
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.security.LocalSyncIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal fun validateExitSigning(arguments: Arguments) {
    if (SIGNING_OPTIONS.none(arguments.options::containsKey)) return
    require(SIGNING_OPTIONS.all { !arguments.options[it].isNullOrBlank() }) {
        "Signed exit requires --signing-keystore, --signing-alias, --node and --governance-context."
    }
    val path = requireNotNull(arguments.databasePath) { "Signed exit requires an explicit --db path." }
    require(Files.isRegularFile(path) && Files.size(path) > 0) { "Provision the database before signed exit." }
}

internal fun exitRuntime(arguments: Arguments, store: SqlDelightStore, actor: SubjectRef): P10Runtime {
    val clock = Clock { Instant.now() }
    val ids = IdGenerator { UUID.randomUUID().toString() }
    val path = arguments.options["signing-keystore"] ?: return P10Runtime(store, clock, ids)
    val key = requireNotNull(SqlDelightRepositories(store.database).actorSigningKeys.findByActor(actor)) {
        "Provision the authenticated actor public key before signed exit."
    }
    val password = requireNotNull(System.getenv("SOTA_P10_SIGNING_PASSWORD")) {
        "SOTA_P10_SIGNING_PASSWORD is required."
    }.toCharArray()
    val signer = try {
        P10SigningKeys.load(Path.of(path), password, arguments.options.getValue("signing-alias"), key.publicKeyBase64)
    } finally { password.fill('\u0000') }
    return SignedP10Runtime(store, LocalSyncIdentity(SotaId(arguments.options.getValue("node")), setOf(actor)),
        Context(arguments.options.getValue("governance-context")), clock, ids, { requested ->
            require(requested == actor) { "Signer is bound to the authenticated actor." }
            signer
        }).exit
}

private val SIGNING_OPTIONS = setOf("signing-keystore", "signing-alias", "node", "governance-context")
