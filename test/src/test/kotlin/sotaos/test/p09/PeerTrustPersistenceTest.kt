package sotaos.test.p09

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.persistence.*
import sotaos.security.RegistryPeerAuthenticator

class PeerTrustPersistenceTest : FunSpec({
    test("SQLite reopen loads keys registry credentials and audit into production root") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            val record = f.record()
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                val peers = SqlDelightPeerTrustRepository(reopened.database)
                peers.find(peerId)?.policy shouldBe f.policy
                RegistryPeerAuthenticator(peers).authenticate("Bearer $token") shouldBe peerId
                peers.audit().size shouldBe 2
                f.exchange(token, record, runtime(reopened)).acceptedThrough shouldBe 1
            }
        }
    }
    test("failed audit insertion rolls back credential mutation") {
        ProductionFixture().use { f ->
            val token = f.enroll()
            f.driver.execute(null, """CREATE TRIGGER fail_provisioning BEFORE INSERT ON provisioning_audit
                BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END""", 0)
            shouldThrow<Exception> { f.runtime.peerProvisioning.rotate(invocation, authorityId, peerId, 1) }
            RegistryPeerAuthenticator(f.peers).authenticate("Bearer $token") shouldBe peerId
            f.peers.find(peerId)?.revision shouldBe 1
            f.peers.audit().size shouldBe 2
        }
    }
    test("provisioning audit rejects update and delete") {
        ProductionFixture().use { f ->
            f.enroll()
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM provisioning_audit", 0) }
            shouldThrow<Exception> { f.driver.execute(null, "UPDATE provisioning_audit SET purpose = 'changed'", 0) }
            f.peers.audit().size shouldBe 2
        }
    }
    test("additive upgrade recreates absent peer tables without losing existing authority") {
        ProductionFixture().use { f ->
            f.driver.execute(null, "DROP TABLE provisioning_audit", 0)
            f.driver.execute(null, "DROP TABLE trusted_peer", 0)
            f.store.close()
            SqlDelightStore(JdbcSqliteDriver("jdbc:sqlite:${f.path}")).use { reopened ->
                SqlDelightPeerTrustRepository(reopened.database).all().size shouldBe 0
                SqlDelightRepositories(reopened.database).authorities.findById(authorityId) shouldBe f.authority
            }
        }
    }
    test("failed provisioning audit rolls back actor key and legacy key audit together") {
        ProductionFixture().use { f ->
            f.enroll()
            f.driver.execute(null, """CREATE TRIGGER fail_key_audit BEFORE INSERT ON provisioning_audit
                BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END""", 0)
            shouldThrow<Exception> {
                f.runtime.keyProvisioning.revoke(invocation, authorityId, remoteActor, "remote-key-1")
            }
            f.repositories.actorSigningKeys.findByActor(remoteActor)?.keyId shouldBe "remote-key-1"
            f.repositories.actorSigningKeys.auditByActor(remoteActor).size shouldBe 1
            f.peers.audit().size shouldBe 2
        }
    }

})
