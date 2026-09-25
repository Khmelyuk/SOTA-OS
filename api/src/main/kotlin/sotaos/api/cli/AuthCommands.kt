package sotaos.api.cli

import sotaos.application.ports.Clock
import sotaos.application.ports.IdGenerator
import sotaos.application.services.AuthenticationService
import sotaos.application.services.IdentityService
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightAuthenticationRepository
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.time.Instant
import java.util.UUID

internal const val LOCAL_SOTA_UNIT = "local"

internal fun authenticateIn(
    repositories: SqlDelightRepositories,
    unit: SotaId,
    providerId: String,
    handle: String
): sotaos.application.ports.AuthenticatedSession {
    val localProvider = LocalPassphraseAuthenticationProvider(repositories.authentication)
    val service = AuthenticationService(
        providers = listOf(localProvider),
        identities = repositories.authentication,
        providersByUnit = mapOf(unit to setOf(localProvider.providerId)),
        now = Instant::now
    )
    val challenge = service.begin(unit, providerId, handle)
    println(challenge.instructions)
    val proof = readSecret("Локальна парольна фраза: ")
    return service.complete(unit, challenge, proof)
}

internal fun runAuthCommand(arguments: Arguments) {
    val options = arguments.options
    if (arguments.subcommand == "create") {
        val handle = options["handle"]?.takeIf(String::isNotBlank) ?: error("auth create requires --handle.")
        val unit = SotaId(options["unit"]?.takeIf(String::isNotBlank) ?: LOCAL_SOTA_UNIT)
        withStore(arguments.databasePath) { store, path ->
            val repositories = SqlDelightRepositories(store.database)
            val clock = Clock { Instant.now() }
            val ids = IdGenerator { UUID.randomUUID().toString() }
            val policy = RightsConstraintDecorator(listOf(NoAgentActionRule))
            val identityService = IdentityService(repositories.persons, repositories.identities, clock, ids, policy)
            val person = identityService.createPerson()
            identityService.createIdentity(
                ProtocolInvocation(SubjectRef.Person(person.id), "Create local identity for ${handle.trim()}"),
                person.id, handle.trim()
            )
            println("Created local Person ${person.id.value} with identity handle '${handle.trim()}'.")
            val password = newPassphrase()
            LocalPassphraseAuthenticationProvider(repositories.authentication)
                .enroll(unit, handle, person.id, password, clock.now())
            println("Local authentication enabled in Sota unit '${unit.value}'.")
            println("Database: $path")
        }
        return
    }
    require(arguments.subcommand == "enroll") {
        "Use auth create --handle HANDLE or auth enroll --person ID --handle HANDLE [--unit UNIT]."
    }
    val person = PersonId(options["person"]?.takeIf(String::isNotBlank)
        ?: error("auth enroll requires --person."))
    val handle = options["handle"]?.takeIf(String::isNotBlank)
        ?: error("auth enroll requires --handle.")
    val unit = SotaId(options["unit"]?.takeIf(String::isNotBlank) ?: LOCAL_SOTA_UNIT)
    withStore(arguments.databasePath) { store, path ->
        val repositories = SqlDelightRepositories(store.database)
        val record = repositories.persons.findById(person) ?: error("Person does not exist.")
        val normalizedHandle = SqlDelightAuthenticationRepository.normalizeHandle(handle)
        require(repositories.identities.findByPerson(person).any {
            SqlDelightAuthenticationRepository.normalizeHandle(it.localHandle) == normalizedHandle
        }) { "The login handle is not an Identity belonging to this Person." }
        println("Первинне прив'язування виконує довірений локальний оператор, який попередньо перевірив особу.")
        val confirmation = "ПРИВ'ЯЗАТИ ${record.id.value}"
        val prompt = "Для підтвердження первинного налаштування введіть: $confirmation\n> "
        require(readTerminalLine(prompt) == confirmation) {
            "Підтвердження не збіглося; автентифікатор не створено."
        }
        val password = newPassphrase()
        LocalPassphraseAuthenticationProvider(repositories.authentication)
            .enroll(unit, normalizedHandle, record.id, password, Instant.now())
        println("Локальну автентифікацію налаштовано для Person ${record.id.value} у Sota unit '${unit.value}'.")
        println("База: $path")
    }
}
