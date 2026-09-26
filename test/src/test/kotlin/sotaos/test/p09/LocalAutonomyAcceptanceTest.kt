package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.application.sync.*
import sotaos.domain.collective.Core
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.io.IOException
import java.util.UUID

class LocalAutonomyAcceptanceTest : FunSpec({
    test("AC-12 AC-17 lost central service cannot destroy Core or disable local creation after restart") {
        ProductionFixture().use { f ->
            f.enroll()
            val before = createLocalCore(f.store, "Before outage")
            val unavailable = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse =
                    throw IOException("central service unavailable")
            }
            shouldThrow<IOException> { f.runtime.synchronize(peerId, unavailable) }
            val during = createLocalCore(f.store, "During outage")
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                val repositories = SqlDelightRepositories(reopened.database)
                listOf(before, during).forEach { (person, core) ->
                    repositories.cores.findById(core.id) shouldBe core
                    repositories.memberships.findActiveFor(person, SubjectRef.Core(core.id)) shouldNotBe null
                }
                shouldThrow<IOException> { runtime(reopened).synchronize(peerId, unavailable) }
                val after = createLocalCore(reopened, "After restart while offline")
                repositories.cores.findById(after.second.id) shouldBe after.second
            }
        }
    }
})

private fun createLocalCore(store: SqlDelightStore, name: String): Pair<PersonId, Core> {
    val repositories = SqlDelightRepositories(store.database)
    val rights = RightsConstraintDecorator(listOf(NoAgentActionRule))
    val clock = Clock { now }
    val ids = IdGenerator { UUID.randomUUID().toString() }
    val person = IdentityService(repositories.persons, repositories.identities, clock, ids, rights).createPerson()
    val authority = AuthorityService(repositories.authorities, clock, ids, rights)
    val collective = CollectiveService(repositories.cores, repositories.memberships, repositories.authorities,
        authority::isValid, clock, ids, rights)
    return person.id to collective.createCore(
        ProtocolInvocation(SubjectRef.Person(person.id), "Local autonomy acceptance"), name, person.id
    )
}
