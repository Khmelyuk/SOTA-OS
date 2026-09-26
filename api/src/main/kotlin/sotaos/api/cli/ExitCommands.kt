package sotaos.api.cli

import sotaos.api.exit.P10Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.persistence.SqlDelightRepositories
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal fun runExitCommand(arguments: Arguments) {
    require(arguments.subcommand == "leave") { "Use exit leave --handle HANDLE --core CORE_ID --out PATH." }
    val handle = requireNotNull(arguments.options["handle"])
    val core = CoreId(requireNotNull(arguments.options["core"]))
    val destination = Path.of(requireNotNull(arguments.options["out"])).toAbsolutePath().normalize()
    withStore(arguments.databasePath) { store, _ ->
        val repositories = SqlDelightRepositories(store.database)
        val unit = SotaId(arguments.options["unit"] ?: LOCAL_SOTA_UNIT)
        val provider = arguments.options["provider"] ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID
        val session = authenticateIn(repositories, unit, provider, handle)
        val confirmation = "ВИХОДЖУ З CORE ${core.value}"
        println("Делегації та відносини цього Core буде закрито; невиконані зобов’язання залишаться.")
        println("Дозволені дані буде записано у $destination перед припиненням членства.")
        require(readTerminalLine("Для підтвердження введіть: $confirmation\n> ") == confirmation) {
            "Підтвердження не збіглося."
        }
        val invocation = ProtocolInvocation(SubjectRef.Person(session.person),
            "Добровільний вихід; зберігаю відповідальність за невиконані зобов’язання",
            Context("core-exit", description = "core:${core.value}"))
        val runtime = P10Runtime(store, Clock { Instant.now() }, IdGenerator { UUID.randomUUID().toString() })
        val receipt = runtime.leave(invocation, core, destination)
        println("Членство припинено. Exit: ${receipt.exitId}; SHA-256: ${receipt.exportSha256}")
    }
}
