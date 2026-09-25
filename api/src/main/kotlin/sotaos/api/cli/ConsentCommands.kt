package sotaos.api.cli

import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.application.services.ConsentService
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.time.Instant
import java.util.UUID

internal fun runConsentCommand(arguments: Arguments) {
    when (arguments.subcommand) {
        "grant" -> grantConsent(arguments)
        "revoke" -> revokeConsent(arguments)
        "list" -> listConsents(arguments)
        else -> error("Use consent grant, consent revoke, or consent list.")
    }
}

private fun Arguments.required(name: String): String = options[name]?.takeIf(String::isNotBlank)
    ?: error("consent " + subcommand.orEmpty() + " requires --$name.")

private fun grantConsent(arguments: Arguments) {
    val options = arguments.options
    val handle = arguments.required("handle")
    val recipient = PersonId(arguments.required("recipient"))
    val purpose = arguments.required("purpose")
    val domain = arguments.required("context")
    val action = arguments.required("action")
    val mission = options["mission"]?.takeIf(String::isNotBlank)?.let(::MissionId)
    val context = Context(domain, mission, options["context-description"].orEmpty())
    val expires = options["valid-until"]?.let(Instant::parse)
    withStore(arguments.databasePath) { store, path ->
        val repos = SqlDelightRepositories(store.database)
        val clock = Clock { Instant.now() }
        val unit = SotaId(options["unit"]?.takeIf(String::isNotBlank) ?: LOCAL_SOTA_UNIT)
        val providerId = options["provider"]?.takeIf(String::isNotBlank)
            ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID
        val session = authenticateIn(repos, unit, providerId, handle)
        require(repos.persons.findById(recipient) != null) { "Recipient Person does not exist." }
        val subject = session.person
        val notice = "Я, Person ${subject.value}, дозволяю Person ${recipient.value} виконати дію " +
            "«$action» щодо мого запису person:${subject.value}, з метою «$purpose», " +
            "у контексті «${context.domain}»${mission?.let { ", місія ${it.value}"}.orEmpty()}" +
            options["context-description"]?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty() +
            (expires?.let { ", до ${it}" } ?: ", без визначеної дати завершення") + ". Згоду можна відкликати."

        println("УМОВИ ЗГОДИ")
        println(notice)
        val affirmation = readTerminalLine("Щоб надати згоду, введіть точно: ${ConsentService.AFFIRMATION_PHRASE}\n> ")
        require(affirmation == ConsentService.AFFIRMATION_PHRASE) { "Підтвердження не збіглося; згоду не записано." }

        val policy = RightsConstraintDecorator(listOf(NoAgentActionRule))
        val service = ConsentService(repos.consents, policy, clock, IdGenerator { UUID.randomUUID().toString() })
        val consent = service.grant(
            invocation = ProtocolInvocation(SubjectRef.Person(subject), purpose, context),
            recipient = SubjectRef.Person(recipient), purpose = purpose, context = context,
            scope = Scope(actions = setOf(action), resources = setOf("person:${subject.value}")),
            notice = notice, affirmation = affirmation, validUntil = expires
        )
        println("Згоду записано: ${consent.id.value}")
        println("Локальна база: $path")
    }
}

private fun revokeConsent(arguments: Arguments) {
    val options = arguments.options
    val handle = arguments.required("handle")
    val consentId = ConsentId(arguments.required("id"))
    withStore(arguments.databasePath) { store, path ->
        val repos = SqlDelightRepositories(store.database)
        val unit = SotaId(options["unit"]?.takeIf(String::isNotBlank) ?: LOCAL_SOTA_UNIT)
        val providerId = options["provider"]?.takeIf(String::isNotBlank)
            ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID
        val session = authenticateIn(repos, unit, providerId, handle)
        val policy = RightsConstraintDecorator(listOf(NoAgentActionRule))
        val service = ConsentService(repos.consents, policy, Clock { Instant.now() },
            IdGenerator { UUID.randomUUID().toString() })
        val consent = repos.consents.findById(consentId) ?: error("Згоду не знайдено.")
        require(consent.subject == session.person) { "Authenticated Person does not own this consent." }
        val confirmation = "ВІДКЛИКАЮ ЗГОДУ ${consentId.value}"
        val prompt = "Щоб відкликати згоду ${consentId.value}, введіть точно: $confirmation\n> "
        require(readTerminalLine(prompt) == confirmation) {
            "Підтвердження не збіглося; згоду не відкликано."
        }
        service.revoke(
            ProtocolInvocation(SubjectRef.Person(session.person), "Відкликати згоду ${consentId.value}",
                consent.context), consent
        )
        println("Згоду відкликано: ${consentId.value}")
        println("Локальна база: $path")
    }
}

private fun listConsents(arguments: Arguments) {
    val options = arguments.options
    val handle = arguments.required("handle")
    withStore(arguments.databasePath) { store, path ->
        val repos = SqlDelightRepositories(store.database)
        val unit = SotaId(options["unit"]?.takeIf(String::isNotBlank) ?: LOCAL_SOTA_UNIT)
        val providerId = options["provider"]?.takeIf(String::isNotBlank)
            ?: LocalPassphraseAuthenticationProvider.PROVIDER_ID
        val session = authenticateIn(repos, unit, providerId, handle)
        val consents = repos.consents.findBySubject(session.person)
        if (consents.isEmpty()) println("Для Person ${session.person.value} згод не знайдено.")
        consents.forEach { consent ->
            println("${consent.id.value} | ${consent.recipient} | ${consent.purpose} | ${consent.scope.actions} | " +
                "${consent.context.domain} | revoked=${consent.revokedAt != null} | " +
                "expires=${consent.validUntil ?: "never"}")
            println("  Умови: ${consent.notice}")
        }
        println("Локальна база: $path")
    }
}
