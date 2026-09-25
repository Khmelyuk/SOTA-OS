package sotaos.test.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightEventStore
import java.io.IOException

class SyncRecoveryTest : FunSpec({
    test("offline transport failure leaves local events available and checkpoints unchanged") {
        withNodes { a, b ->
            a.service.recordLocal(record("offline", a.id))
            val disconnected = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse = throw IOException("offline")
            }
            shouldThrow<IOException> { a.service.synchronize(b.id, disconnected) }
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
            a.repository.records().single().event.id shouldBe EventId("offline")
        }
    }

    test("lost acknowledgement is safe to retry and does not duplicate events or conflicts") {
        withNodes { a, b ->
            a.service.recordLocal(record("a", a.id, claim("one")))
            b.service.recordLocal(record("b", b.id, claim("two")))
            val loseReply = object : SyncTransport {
                override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
                    transportTo(b).exchange(peer, request)
                    throw IOException("reply lost")
                }
            }
            shouldThrow<IOException> { a.service.synchronize(b.id, loseReply) }
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
            repeat(3) { a.service.synchronize(b.id, transportTo(b)) }
            a.repository.records().size shouldBe 2
            b.repository.records().size shouldBe 2
            a.repository.conflicts().size shouldBe 1
            b.repository.conflicts() shouldBe a.repository.conflicts()
        }
    }

    test("cursors, conflicts and origin survive SQLite reopen") {
        withNodes { a, b ->
            a.service.recordLocal(record("a", a.id, claim("one")))
            b.service.recordLocal(record("b", b.id, claim("two")))
            a.service.synchronize(b.id, transportTo(b))
            val checkpoint = a.repository.checkpoint(b.id)
            // A second connection models a restarted service, without relying on in-memory sync state.
            TestSyncNode("a", a.path).use { restarted ->
                restarted.repository.checkpoint(b.id) shouldBe checkpoint
                restarted.service.projection(sharedAuthority) shouldBe a.service.projection(sharedAuthority)
                restarted.service.synchronize(b.id, transportTo(b))
                restarted.repository.records().size shouldBe 2
            }
        }
    }

    test("batches beyond the page size converge without skipping events") {
        withNodes { a, b ->
            a.repository.transaction {
                repeat(MAX_SYNC_BATCH + 1) { index ->
                    a.repository.append(record("event-$index", a.id))
                }
            }
            a.service.synchronize(b.id, transportTo(b))
            b.repository.records().size shouldBe MAX_SYNC_BATCH
            a.service.synchronize(b.id, transportTo(b))
            b.repository.records().size shouldBe MAX_SYNC_BATCH + 1
            a.repository.records().toSet() shouldBe b.repository.records().toSet()
        }
    }

    test("existing Core Loop events are captured as additive facts without modifying their payload") {
        withNodes { a, b ->
            val original = record("core-loop", a.id).event
            SqlDelightEventStore(a.store.database).append(original)
            a.service.synchronize(b.id, transportTo(b))
            b.repository.records().single().event shouldBe original
            b.repository.records().single().assertion shouldBe null
        }
    }

    test("journal and conflict tables reject direct update and delete") {
        withNodes { a, b ->
            a.service.recordLocal(record("a", a.id, claim("one")))
            b.service.recordLocal(record("b", b.id, claim("two")))
            a.service.synchronize(b.id, transportTo(b))
            val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${a.path}")
            driver.use {
                shouldThrow<java.sql.SQLException> { it.execute(null, "DELETE FROM sync_journal", 0) }
                shouldThrow<java.sql.SQLException> {
                    it.execute(null, "UPDATE sync_conflict SET entity_id = 'changed'", 0)
                }
            }
            a.repository.records().size shouldBe 2
            a.repository.conflicts().size shouldBe 1
        }
    }
})
