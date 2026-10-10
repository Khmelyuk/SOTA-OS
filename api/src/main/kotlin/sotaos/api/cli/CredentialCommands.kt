package sotaos.api.cli

import sotaos.api.identity.P01CredentialsRuntime
import sotaos.application.ports.Clock
import sotaos.domain.shared.SotaId
import java.time.Instant

internal fun runCredentialCommand(arguments: Arguments) {
    require(arguments.options.keys.all { it in setOf("handle", "unit") }) {
        "Credential commands accept only --handle, --unit and --db; secrets require a terminal."
    }
    val handle = requireNotNull(arguments.options["handle"]?.takeIf(String::isNotBlank)) {
        "Credential command requires --handle."
    }
    val unit = SotaId(arguments.options["unit"]?.takeIf(String::isNotBlank) ?: LOCAL_SOTA_UNIT)
    withStore(arguments.databasePath) { store, _ ->
        val runtime = P01CredentialsRuntime(store, unit, Clock { Instant.now() })
        val challenge = runtime.begin(handle)
        if (arguments.subcommand == "revoke") {
            val confirmation = "ВІДКЛИКАТИ ${challenge.subjectHint}"
            require(readTerminalLine("Для відкликання введіть: $confirmation\n> ") == confirmation) {
                "Відкликання не підтверджено."
            }
        }
        val proof = readSecret("Локальна парольна фраза: ")
        try {
            if (arguments.subcommand == "rotate") {
                runtime.rotate(challenge, proof, newPassphrase())
                println("Парольну фразу змінено; попередня більше не дає доступу.")
            } else {
                runtime.revoke(challenge, proof)
                println("Локальний credential відкликано; Person та Identity збережено.")
            }
        } finally {
            proof.fill('\u0000')
        }
    }
}
