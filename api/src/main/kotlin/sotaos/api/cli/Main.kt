package sotaos.api.cli

import java.nio.file.Path

fun main(args: Array<String>) {
    val parsed = parseArguments(args)
    when (parsed.command) {
        "help", "--help", "-h" -> printHelp()
        "init" -> withStore(parsed.databasePath) { _, path ->
            println("SOTA OS database ready: $path")
        }
        "demo" -> withStore(parsed.databasePath) { store, path ->
            runCoreLoopDemo(store.database)
            println("Core Loop demo completed and stored locally.")
            println("Database: $path")
        }
        "consent" -> runConsentCommand(parsed)
        "auth" -> runAuthCommand(parsed)
        "exit" -> runExitCommand(parsed)
        else -> {
            System.err.println("Unknown command: ${parsed.command}")
            printHelp()
            kotlin.system.exitProcess(2)
        }
    }
}

internal data class Arguments(
    val command: String,
    val subcommand: String?,
    val databasePath: Path?,
    val options: Map<String, String>
)

private fun parseArguments(args: Array<String>): Arguments {
    var command = "help"
    var subcommand: String? = null
    var databasePath: Path? = null
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        when {
            argument == "--db" -> {
                require(index + 1 < args.size) { "--db requires a file path." }
                databasePath = Path.of(args[index + 1]).toAbsolutePath().normalize()
                index += 2
            }
            argument.startsWith("--") -> {
                require(index + 1 < args.size) { "$argument requires a value." }
                options[argument.removePrefix("--")] = args[index + 1]
                index += 2
            }
            else -> {
                when {
                    command == "help" -> command = argument
                    subcommand == null -> subcommand = argument
                    else -> error("Unexpected argument: $argument")
                }
                index += 1
            }
        }
    }
    return Arguments(command, subcommand, databasePath, options)
}

private fun printHelp() {
    println(
        """
        SOTA OS local CLI

        Usage:
          ./gradlew :api:run --args="help"
          ./gradlew :api:run --args="init [--db /path/to/sota-os.db]"
          ./gradlew :api:run --args="demo [--db /path/to/sota-os.db]"
          ./gradlew :api:run --args="auth create --handle HANDLE [--unit UNIT] [--db PATH]"
          ./gradlew :api:run --args="auth enroll --person PERSON_ID --handle HANDLE [--unit UNIT] [--db PATH]"
          ./gradlew :api:run --args="consent grant --handle HANDLE --recipient PERSON_ID --purpose TEXT --context DOMAIN --action ACTION [--provider ID] [--mission ID] [--valid-until ISO_INSTANT] [--db PATH]"
          ./gradlew :api:run --args="consent revoke --handle HANDLE --id CONSENT_ID [--provider ID] [--db PATH]"
          ./gradlew :api:run --args="consent list --handle HANDLE [--provider ID] [--db PATH]"

        Commands:
          init   Create the local database schema if the database is new.
          demo   Run one authorized end-to-end Core Loop and persist its records.
          consent grant   Display specific consent terms and record only after exact typed confirmation.
          consent revoke Revoke a previously granted consent after exact typed confirmation.
          consent list   List consent records belonging to a Person.
          auth create    Create a Person, local identity, and local passphrase.
          auth enroll    Bootstrap a local passphrase for an existing Person identity.
          exit leave --handle HANDLE --core CORE_ID --out PATH [--db PATH]
                 Export allowed data and terminate your membership in one Core.
          help   Show this help.

        The default database path is ${'$'}XDG_DATA_HOME/sota-os/sota-os.db,
        or ~/.local/share/sota-os/sota-os.db when XDG_DATA_HOME is unset.
        """.trimIndent()
    )
}
