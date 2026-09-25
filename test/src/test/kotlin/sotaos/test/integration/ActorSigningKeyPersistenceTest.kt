package sotaos.test.integration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.ActorSigningKeyRecord
import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SubjectRef
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.db.SotaOsDatabase
import sotaos.security.Ed25519EventSigner
import java.nio.file.Files
import java.time.Instant

class ActorSigningKeyPersistenceTest : FunSpec({
    test("active actor public keys survive SQLite reopen and guarded revoke") {
        val path = Files.createTempFile("sota-os-actor-key", ".db")
        val actor = SubjectRef.Person(PersonId("person-key"))
        val signer = Ed25519EventSigner.generate()
        val record = ActorSigningKeyRecord(actor, "key-1", signer.publicKeyEncoded())
        val enrolledAt = Instant.parse("2026-09-25T00:00:00Z")
        val rotatedAt = enrolledAt.plusSeconds(60)
        val revokedAt = rotatedAt.plusSeconds(60)
        val rotated = ActorSigningKeyRecord(actor, "key-2", Ed25519EventSigner.generate().publicKeyEncoded())
        try {
            JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                SotaOsDatabase.Schema.create(driver)
                SqlDelightStore(driver).use { store ->
                    SqlDelightRepositories(store.database).actorSigningKeys.save(record, enrolledAt)
                    SqlDelightRepositories(store.database).actorSigningKeys.save(rotated, rotatedAt)
                }
            }
            JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                SqlDelightStore(driver).use { store ->
                    val keys = SqlDelightRepositories(store.database).actorSigningKeys
                    keys.findByActor(actor) shouldBe rotated
                    shouldThrow<IllegalArgumentException> { keys.revoke(actor, "old-key", revokedAt) }
                    keys.revoke(actor, "key-2", revokedAt)
                    keys.findByActor(actor) shouldBe null
                    keys.auditByActor(actor).map { it.operation.name } shouldBe listOf("ENROLL", "ROTATE", "REVOKE")
                    shouldThrow<Exception> {
                        driver.execute(null, "UPDATE actor_signing_key_audit SET operation = 'TAMPERED'", 0)
                    }
                    shouldThrow<Exception> {
                        driver.execute(null, "DELETE FROM actor_signing_key_audit", 0)
                    }
                    keys.auditByActor(actor).size shouldBe 3
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
})
