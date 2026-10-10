package sotaos.test.rehearsal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.application.sync.SyncDirection
import sotaos.domain.identity.Person
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import sotaos.persistence.db.SotaOsDatabase
import sotaos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal val rehearsalClock = Clock { Instant.now() }
internal val rehearsalIds = IdGenerator { UUID.randomUUID().toString() }
internal val rehearsalRights = RightsConstraintDecorator(listOf(NoAgentActionRule))
internal val governance = Context("rehearsal-governance")
internal val work = Context("rehearsal-work")

/** Fixture-only bootstrap. Each access closes SQLite before another process opens the same node. */
internal class RehearsalNode(val name: String, directory: Path) {
    val id = SotaId(name)
    val path: Path = directory.resolve("$name.db")
    val signer = Ed25519EventSigner.generate()
    private val password = UUID.randomUUID().toString()
    private val operatorPassword = UUID.randomUUID().toString()
    val person: Person
    private val operator: Person
    val actor get() = SubjectRef.Person(person.id)
    val local get() = LocalSyncIdentity(id, setOf(actor))

    init {
        val people = localAccess { _, repositories ->
            val identity = IdentityService(repositories.persons, repositories.identities,
                rehearsalClock, rehearsalIds, rehearsalRights)
            listOf("author" to password, "operator" to operatorPassword).map { (handle, secret) ->
                val created = identity.createPerson()
                identity.createIdentity(ProtocolInvocation(SubjectRef.Person(created.id), "Rehearsal bootstrap"),
                    created.id, handle)
                LocalPassphraseAuthenticationProvider(repositories.authentication)
                    .enroll(id, handle, created.id, secret.toCharArray(), rehearsalClock.now())
                requireNotNull(repositories.persons.findById(created.id))
            }
        }
        person = people[0]
        operator = people[1]
    }

    fun <T> access(block: (SqlDelightStore, SqlDelightRepositories) -> T): T {
        val create = !Files.exists(path)
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        if (create) SotaOsDatabase.Schema.create(driver)
        return SqlDelightStore(driver).use { store ->
            block(store, SqlDelightRepositories(store.database))
        }
    }

    fun <T> localAccess(block: (SqlDelightStore, SqlDelightRepositories) -> T): T = access { store, repositories ->
        store.withLocalAccess { block(store, repositories) }
    }

    fun runtime(store: SqlDelightStore) = P09Runtime(store, local, governance, rehearsalClock, rehearsalIds)

    fun invocation(repositories: SqlDelightRepositories, context: Context, asOperator: Boolean = false)
        : ProtocolInvocation {
        val provider = LocalPassphraseAuthenticationProvider(repositories.authentication)
        val authentication = AuthenticationService(listOf(provider), repositories.authentication,
            mapOf(id to setOf(provider.providerId)), rehearsalClock::now)
        val challenge = authentication.begin(id, provider.providerId, if (asOperator) "operator" else "author")
        val principal = authentication.complete(id, challenge,
            (if (asOperator) operatorPassword else password).toCharArray())
        check(principal.person == if (asOperator) operator.id else person.id)
        return ProtocolInvocation(SubjectRef.Person(principal.person), "Two-node rehearsal", context)
    }

    fun enroll(other: RehearsalNode, core: CoreId): String = localAccess { store, repositories ->
        val authorityId = AuthorityId("provision-$name")
        // Initial trust anchor is explicit fixture input; subsequent enrollment uses governed APIs.
        repositories.authorities.save(Authority(authorityId, SubjectRef.Core(core), SubjectRef.Person(operator.id),
            Scope(setOf("peer.enroll", "key.enroll"), setOf("peer:${other.name}",
                actorKeyTarget(actor), actorKeyTarget(other.actor))), governance,
            AuthorityBasis(note = "Independent rehearsal operator bootstrap"),
            Validity(rehearsalClock.now(), null), SubjectRef.Core(core), LifecycleState.ACTIVE))
        val runtime = runtime(store)
        val invocation = invocation(repositories, governance, asOperator = true)
        listOf(this, other).forEach { node ->
            runtime.keyProvisioning.enroll(invocation, authorityId,
                ActorSigningKeyRecord(node.actor, "key-${node.name}", node.signer.publicKeyEncoded()))
        }
        runtime.peerProvisioning.enroll(invocation, authorityId, PeerTrustPolicy(other.id, setOf(other.actor),
            setOf(work, Context("core-exit", description = "core:${core.value}")),
            SyncDirection.entries.toSet(), setOf(id, other.id), setOf(id, other.id)))
    }
}
