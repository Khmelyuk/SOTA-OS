package sotaos.test.acceptance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.api.identity.P01CredentialsRuntime
import sotaos.application.ports.*
import sotaos.application.services.AuthenticationService
import sotaos.domain.identity.Identity
import sotaos.domain.identity.Person
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.persistence.db.SotaOsDatabase
import java.nio.file.Files
import java.time.Instant

internal class CredentialFixture : AutoCloseable {
    val path = Files.createTempFile("p01-lifecycle-", ".db")
    var driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    var store: SqlDelightStore
    var now: Instant = Instant.parse("2026-10-09T00:00:00Z")
    val unit = SotaId("local")
    val person = PersonId("owner")
    val handle = "owner"
    val oldPassword = "original-test-passphrase"
    val newPassword = "replacement-test-passphrase"
    val repositories get() = SqlDelightRepositories(store.database)
    val authentication get() = SqlDelightAuthenticationRepository(store.database)
    val mutations get() = SqlDelightLocalCredentialLifecycleRepository(store.database)
    val provider get() = LocalPassphraseAuthenticationProvider(authentication)
    val runtime get() = P01CredentialsRuntime(store, unit, Clock { now })

    init {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        SotaOsDatabase.Schema.create(driver)
        store = SqlDelightStore(driver)
        repositories.persons.save(Person(person, emptyList(), now))
        repositories.identities.save(Identity(IdentityId("identity"), person, handle))
        provider.enroll(unit, handle, person, oldPassword.toCharArray(), now)
    }

    fun service(repository: AuthenticationRepository = authentication): AuthenticationService {
        val adapter = LocalPassphraseAuthenticationProvider(repository)
        return AuthenticationService(listOf(adapter), repository,
            mapOf(unit to setOf(adapter.providerId)), { now })
    }

    fun login(password: String = oldPassword): AuthenticatedSession {
        val service = service()
        return service.complete(unit, service.begin(unit, provider.providerId, handle), password.toCharArray())
    }

    fun credential() = requireNotNull(authentication.findLocalCredential(unit, handle))
    fun history() = store.database.localCredentialLifecycleQueries.credentialHistory(unit.value, handle).executeAsList()

    fun reopen() {
        store.close()
        driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        store = SqlDelightStore(driver)
    }

    override fun close() {
        store.close()
        Files.deleteIfExists(path)
    }
}
