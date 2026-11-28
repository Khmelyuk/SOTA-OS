package sotaos.api.cli

import sotaos.application.ports.ExitStatus
import sotaos.application.services.ExitStatusService
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.nio.file.Files

internal fun runExitStatus(arguments: Arguments) {
    validateExitStatus(arguments)
    val core = CoreId(arguments.options.getValue("core"))
    withStore(arguments.databasePath) { store, _ ->
        val session = authenticateIn(SqlDelightRepositories(store.database),
            SotaId(arguments.options["unit"] ?: LOCAL_SOTA_UNIT),
            arguments.options["provider"] ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID,
            arguments.options.getValue("handle"))
        val query = ExitStatusService(SqlDelightExitRepository(store.database),
            SqlDelightExitScopeRepository(store.database), RightsConstraintDecorator(listOf(NoAgentActionRule)))
        val invocation = ProtocolInvocation(SubjectRef.Person(session.person),
            "Перегляд стану власного виходу", Context("core-exit", description = "core:${core.value}"))
        val status = store.withLocalAccess { query.status(invocation, core) }
        println(formatExitStatus(status))
    }
}

internal fun validateExitStatus(arguments: Arguments) {
    require(arguments.options.keys.all { it in setOf("handle", "core", "unit", "provider") }) {
        "exit status accepts only --handle, --core, --unit, --provider and --db; no output or signing options."
    }
    require(!arguments.options["handle"].isNullOrBlank() && !arguments.options["core"].isNullOrBlank()) {
        "Use exit status --handle HANDLE --core CORE_ID --db PATH."
    }
    val path = requireNotNull(arguments.databasePath) { "exit status requires an explicit existing --db path." }
    require(Files.isRegularFile(path) && Files.size(path) > 0) { "exit status requires an existing database." }
}

internal fun formatExitStatus(status: ExitStatus): String = buildString {
    appendLine("Core: ${status.target.core.value}")
    appendLine("Активне членство: ${if (status.membershipActive) "так" else "ні"}")
    if (status.history.isEmpty()) appendLine("Записів про власний вихід із цього Core немає.")
    status.history.forEach { progress ->
        appendLine("Exit: ${progress.process.id}; stage: ${progress.process.stage}; " +
            "updated: ${progress.process.updatedAt}")
        appendLine("SHA-256 архіву в БД: ${progress.archiveSha256 ?: "архів ще не створено"}")
    }
    append("Локальний стан; наявність архіву в БД не підтверджує його доставку у файл. ")
    append("Жоден етап виходу не виконувався.")
}
