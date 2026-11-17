package sotaos.test.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.services.AuthorityException
import sotaos.application.services.AuthorityService
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.db.SotaOsDatabase
import sotaos.test.architecture_invariants.AuthorityFixture
import sotaos.test.fakes.SequentialIdGenerator
import sotaos.test.fakes.testInvocation
import java.nio.file.Files
import java.nio.file.Path

class SqlDelightAuthorityBoundaryTest : FunSpec({
    test("forged revocation is rejected and legitimate stale-input revocation preserves stored terms after reopen") {
        val f = AuthorityFixture()
        val stored = f.source.copy(scope = Scope(setOf("limited")))
        withAuthorityDatabase { path ->
            openAuthorityDatabase(path).use { store ->
                val repository = SqlDelightRepositories(store.database).authorities
                repository.save(stored)
                val service = AuthorityService(repository, f.clock, SequentialIdGenerator(), f.rights)
                shouldThrow<IllegalArgumentException> {
                    service.revoke(testInvocation(f.recipient), f.source.copy(issuer = f.recipient), "forged")
                }
                repository.findById(stored.id) shouldBe stored
                service.revoke(testInvocation(f.issuer), f.source, "legitimate")
            }
            openAuthorityDatabase(path).use { reopened ->
                SqlDelightRepositories(reopened.database).authorities.findById(stored.id) shouldBe
                    stored.copy(state = LifecycleState.REVOKED)
            }
        }
    }

    test("persisted ACTIVE grants that have expired cannot delegate after database reopen") {
        val f = AuthorityFixture()
        val expired = f.source.copy(validity = Validity(f.clock.now().minusSeconds(1), f.clock.now()))
        withAuthorityDatabase { path ->
            openAuthorityDatabase(path).use { store ->
                SqlDelightRepositories(store.database).authorities.save(expired)
            }
            openAuthorityDatabase(path).use { reopened ->
                val repository = SqlDelightRepositories(reopened.database).authorities
                val service = AuthorityService(repository, f.clock, SequentialIdGenerator(), f.rights)
                shouldThrow<AuthorityException> {
                    service.grant(testInvocation(f.holder), f.holder, f.recipient, f.scope, f.context,
                        AuthorityBasis(), Validity(f.clock.now(), null), f.issuer)
                }
                repository.findActiveFor(f.recipient, f.context) shouldBe emptyList()
                repository.findById(expired.id) shouldBe expired
                f.checks shouldBe emptyList()
            }
        }
    }
})

private fun openAuthorityDatabase(path: Path): SqlDelightStore {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    if (Files.size(path) == 0L) SotaOsDatabase.Schema.create(driver)
    return SqlDelightStore(driver)
}

private fun withAuthorityDatabase(block: (Path) -> Unit) {
    val path = Files.createTempFile("sota-authority-", ".db")
    try { block(path) } finally { Files.deleteIfExists(path) }
}
