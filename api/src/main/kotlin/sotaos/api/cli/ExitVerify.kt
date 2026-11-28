package sotaos.api.cli

import sotaos.domain.shared.*
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.persistence.SqlDelightRepositories
import java.nio.file.Files
import java.nio.file.Path

internal fun runExitVerify(arguments: Arguments) {
    validateExitVerify(arguments)
    val core = CoreId(arguments.options.getValue("core"))
    val exitId = arguments.options.getValue("id")
    val file = Path.of(arguments.options.getValue("file")).toAbsolutePath().normalize()
    withStore(arguments.databasePath) { store, _ ->
        val session = authenticateIn(SqlDelightRepositories(store.database),
            SotaId(arguments.options["unit"] ?: LOCAL_SOTA_UNIT),
            arguments.options["provider"] ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID,
            arguments.options.getValue("handle"))
        val actor = SubjectRef.Person(session.person)
        val invocation = ProtocolInvocation(actor, "Перевірка файла власного архіву виходу",
            Context("core-exit", description = "core:${core.value}"))
        val runtime = exitRuntime(arguments, store, actor)
        val document = store.withLocalAccess { runtime.verifyArchive(invocation, core, exitId, file) }
        println("Файл відповідає збереженому архіву: $file; Exit: ${document.exitId}; SHA-256: ${document.sha256}")
        println("Стан виходу та членства не змінено.")
    }
}

internal fun validateExitVerify(arguments: Arguments) {
    val allowed = setOf("handle", "core", "id", "file", "unit", "provider")
    require(arguments.options.keys.all { it in allowed }) {
        "exit verify accepts only --handle, --core, --id, --file, --unit, --provider and --db."
    }
    require(listOf("handle", "core", "id", "file").all { !arguments.options[it].isNullOrBlank() }) {
        "Use exit verify --handle HANDLE --core CORE_ID --id EXIT_ID --file PATH --db PATH."
    }
    val path = requireNotNull(arguments.databasePath) { "exit verify requires an explicit existing --db path." }
    require(Files.isRegularFile(path) && Files.size(path) > 0) { "exit verify requires an existing database." }
}
