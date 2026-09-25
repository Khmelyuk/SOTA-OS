package sotaos.test.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.IdGenerator
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.persistence.SqlDelightEventStore
import sotaos.sync.SyncService

class SyncAtomicityTest : FunSpec({
    test("checkpoint failure rolls back incoming events, metadata and conflicts as one transaction") {
        withNodes { a, b ->
            a.service.recordLocal(record("local", a.id, claim("one")))
            val failing = object : SyncRepository by a.repository {
                override fun saveCheckpoint(peer: SotaId, checkpoint: PeerCheckpoint) = error("disk failure")
            }
            val service = SyncService(a.id, failing, recordCodec, admitFixtures, IdGenerator { "request" })
            val remote = record("remote", b.id, claim("two"))
            shouldThrow<IllegalStateException> {
                service.receive(b.id, SyncRequest("r", b.id, 0, SyncBatch(0, 1, listOf(remote))))
            }
            a.repository.records().size shouldBe 1
            a.repository.conflicts() shouldBe emptyList()
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
            SqlDelightEventStore(a.store.database).findById(remote.event.id) shouldBe null
        }
    }

    test("export denial occurs before transport and leaves remote node untouched") {
        withNodes { a, b ->
            a.service.recordLocal(record("private", a.id))
            val denyExport = SyncAdmission { _, direction, _ ->
                check(direction != SyncDirection.EXPORT) { "Sharing denied" }
            }
            val service = SyncService(a.id, a.repository, recordCodec, denyExport, IdGenerator { "request" })
            shouldThrow<IllegalStateException> { service.synchronize(b.id, transportTo(b)) }
            b.repository.records() shouldBe emptyList()
            a.repository.checkpoint(b.id) shouldBe PeerCheckpoint()
        }
    }

    test("incoming exchange rolls back when its outgoing response is not authorized") {
        withNodes { a, b ->
            val denyExport = SyncAdmission { _, direction, _ -> check(direction != SyncDirection.EXPORT) }
            val service = SyncService(a.id, a.repository, recordCodec, denyExport, IdGenerator { "request" })
            val remote = record("remote", b.id)
            shouldThrow<IllegalStateException> {
                service.receive(b.id, SyncRequest("r", b.id, 0, SyncBatch(0, 1, listOf(remote))))
            }
            a.repository.records() shouldBe emptyList()
            SqlDelightEventStore(a.store.database).findById(remote.event.id) shouldBe null
        }
    }

    test("pre-P09 databases gain the sync tables without losing existing events") {
        withNodes { a, b ->
            val original = record("legacy", a.id).event
            SqlDelightEventStore(a.store.database).append(original)
            JdbcSqliteDriver("jdbc:sqlite:${a.path}").use { driver ->
                listOf("sync_journal", "sync_checkpoint", "sync_conflict").forEach { table ->
                    driver.execute(null, "DROP TABLE $table", 0)
                }
            }
            TestSyncNode("a", a.path).use { upgraded ->
                upgraded.service.synchronize(b.id, transportTo(b))
                upgraded.repository.records().single().event shouldBe original
                b.repository.records().single().event shouldBe original
            }
            TestSyncNode("a", a.path).use { reopened -> reopened.repository.records().size shouldBe 1 }
        }
    }
})
