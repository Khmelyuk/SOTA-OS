package sotaos.api.cli

import sotaos.domain.shared.*
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.persistence.SqlDelightRepositories
import java.nio.file.Files
import java.nio.file.Path

internal fun runExitArchive(arguments: Arguments) {
    validateExitArchive(arguments)
    val core = CoreId(arguments.options.getValue("core"))
    val exitId = arguments.options.getValue("id")
    val destination = Path.of(arguments.options.getValue("out")).toAbsolutePath().normalize()
    withStore(arguments.databasePath) { store, _ ->
        val session = authenticateIn(SqlDelightRepositories(store.database),
            SotaId(arguments.options["unit"] ?: LOCAL_SOTA_UNIT),
            arguments.options["provider"] ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID,
            arguments.options.getValue("handle"))
        val actor = SubjectRef.Person(session.person)
        val invocation = ProtocolInvocation(actor, "Повторне отримання власного архіву виходу",
            Context("core-exit", description = "core:${core.value}"))
        val runtime = exitRuntime(arguments, store, actor)
        val document = store.withLocalAccess { runtime.copyArchive(invocation, core, exitId, destination) }
        println("Архів записано: $destination; Exit: ${document.exitId}; SHA-256: ${document.sha256}")
        println("Стан виходу та членства не змінено.")
    }
}

internal fun validateExitArchive(arguments: Arguments) {
    val allowed = setOf("handle", "core", "id", "out", "unit", "provider")
    require(arguments.options.keys.all { it in allowed }) {
        "exit archive accepts only --handle, --core, --id, --out, --unit, --provider and --db."
    }
    require(listOf("handle", "core", "id", "out").all { !arguments.options[it].isNullOrBlank() }) {
        "Use exit archive --handle HANDLE --core CORE_ID --id EXIT_ID --out NEW_PATH --db PATH."
    }
    val path = requireNotNull(arguments.databasePath) { "exit archive requires an explicit existing --db path." }
    require(Files.isRegularFile(path) && Files.size(path) > 0) { "exit archive requires an existing database." }
}
