package sotaos.api.cli

import sotaos.agent.research.*
import sotaos.application.ports.Clock
import sotaos.application.services.AuthenticationService
import sotaos.domain.shared.*
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.persistence.SqlDelightRepositories
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.nio.file.Path
import java.time.Instant

private val researchOptions = mapOf(
    "setup" to emptySet(), "revoke" to setOf("authority"), "import" to setOf("authority", "file"),
    "search" to setOf("authority", "query"), "argument" to setOf("authority", "file"),
    "report" to setOf("authority", "out"), "draft" to setOf("authority", "file", "evidence"),
    "approve" to setOf("authority", "draft"), "fetch-moltbook" to setOf("authority")
)

internal fun runResearchCommand(arguments: Arguments) {
    val operation = arguments.subcommand ?: error("Research requires a subcommand; see agent/README.md.")
    val permitted = researchOptions[operation] ?: error("Unsupported research command.")
    require(arguments.options.keys.all { it in permitted || it in setOf("handle", "unit", "provider") }) {
        "Unsupported research option; secrets cannot be supplied as arguments."
    }
    val handle = arguments.researchRequired("handle")
    permitted.forEach { arguments.researchRequired(it) }
    val unit = SotaId(arguments.options["unit"] ?: LOCAL_SOTA_UNIT)
    val provider = arguments.options["provider"] ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID
    withStore(arguments.databasePath) { store, path ->
        val repos = SqlDelightRepositories(store.database)
        val rights = RightsConstraintDecorator(listOf(NoAgentActionRule))
        if (operation in setOf("setup", "revoke")) {
            val phrase = if (operation == "setup") "СТВОРИТИ RESEARCH CORE" else "ВІДКЛИКАТИ RESEARCH"
            println(if (operation == "setup")
                "Новий локальний Core делегує вам research-права на 24 години. Публікація вимкнена."
                else "Добровільна відмова від власного research-повноваження та всіх його дочірніх делегувань.")
            require(readTerminalLine("Для підтвердження введіть: $phrase\n> ") == phrase) { "Скасовано." }
            val session = authenticateIn(repos, unit, provider, handle)
            if (operation == "setup") {
                println("Research authority: ${setupResearch(repos, session, rights).value}")
            } else {
                surrenderResearch(repos, session, AuthorityId(arguments.researchRequired("authority")), rights)
                println("Research authority revoked.")
            }
        } else {
            val adapter = LocalPassphraseAuthenticationProvider(repos.authentication)
            val auth = AuthenticationService(listOf(adapter), repos.authentication,
                mapOf(unit to setOf(adapter.providerId)), Instant::now)
            val challenge = auth.begin(unit, provider, handle)
            println(challenge.instructions)
            val guard = ResearchAuthorization(auth, repos.authorities, Clock { Instant.now() }, unit,
                RESEARCH_CONTEXT, rights)
            val workspace = path.resolveSibling("${path.fileName}.research").resolve(unitDirectory(unit))
            val agent = ResearchAgent(ResearchJournal(workspace), guard)
            val access = ResearchAccess(challenge, readSecret("Локальна парольна фраза: "),
                AuthorityId(arguments.researchRequired("authority")), "Local research $operation")
            try {
                executeResearch(operation, arguments, agent, access)
            } finally {
                access.proof.fill('\u0000')
            }
        }
    }
}

private fun executeResearch(operation: String, args: Arguments, agent: ResearchAgent, access: ResearchAccess) {
    when (operation) {
        "import" -> {
            val data = ResearchFiles.discussions(Path.of(args.researchRequired("file")))
            agent.ingest(access, data).forEach { println("Source: $it") }
        }
        "search" -> agent.search(access, args.researchRequired("query")).forEach { println("Source: $it") }
        "argument" -> println("Argument: ${importResearchArgument(args, agent, access)}")
        "report" -> {
            val path = Path.of(args.researchRequired("out")).toAbsolutePath().normalize()
            require(!java.nio.file.Files.exists(path)) { "Output already exists; choose a new report path." }
            ResearchFiles.output(path, agent.report(access))
            println("Звіт збережено: $path")
        }
        "draft" -> {
            val text = ResearchFiles.text(Path.of(args.researchRequired("file")))
            val evidence = args.researchRequired("evidence").split(',')
            println("Draft: ${agent.createDraft(access, text, evidence)}")
        }
        "approve" -> println("Approval: ${agent.reviewDraft(access, args.researchRequired("draft")) { preview ->
            printResearchPreview(preview)
            val phrase = "ПОГОДЖУЮ ${preview.contentHash}"
            readTerminalLine("Введіть точно: $phrase\n> ") == phrase
        }}")
        "fetch-moltbook" -> println("Moltbook: ${agent.fetchMoltbook(access, MoltbookAdapter())}")
    }
}

private fun printResearchPreview(preview: DraftPreview) {
    require(preview.text.none {
        (it.isISOControl() && it !in "\n\t") || Character.getType(it) == Character.FORMAT.toInt()
    }) { "Unsafe terminal control in draft." }
    println("Чернетка ${preview.id}. Локальна перевірка; публікація вимкнена.")
    println("----- ТЕКСТ ЧЕРНЕТКИ -----")
    println(preview.text)
    println("----- КІНЕЦЬ ЧЕРНЕТКИ -----")
    println("SHA-256: ${preview.contentHash}")
}

private fun unitDirectory(unit: SotaId): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(unit.value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun Arguments.researchRequired(name: String): String = options[name]?.takeIf(String::isNotBlank)
    ?: error("research $subcommand requires --$name.")
