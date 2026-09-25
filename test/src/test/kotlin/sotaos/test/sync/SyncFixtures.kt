package sotaos.test.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sotaos.application.ports.IdGenerator
import sotaos.application.sync.*
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.*
import sotaos.persistence.*
import sotaos.persistence.db.SotaOsDatabase
import sotaos.protocol.*
import sotaos.sync.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal val syncTime: Instant = Instant.parse("2026-09-25T00:00:00Z")
internal val syncContext = Context("sync-test")
internal val sharedAuthority = SyncEntity(SyncEntityKind.AUTHORITY, "shared-authority")
internal val recordCodec = JsonSyncRecordCodec()
internal val messageCodec = JsonSyncMessageCodec()

/** Test-only admission for already trusted fixtures, never a production policy. */
internal val admitFixtures = SyncAdmission { _, _, _ -> }

internal class TestSyncNode(
    name: String,
    val path: Path,
    admission: SyncAdmission = admitFixtures
) : AutoCloseable {
    val id = SotaId(name)
    private val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    val store: SqlDelightStore
    val repository: SqlDelightSyncRepository
    val service: SyncService

    init {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        if (Files.size(path) == 0L) SotaOsDatabase.Schema.create(driver)
        store = SqlDelightStore(driver)
        repository = SqlDelightSyncRepository(store.database, recordCodec)
        service = SyncService(id, repository, recordCodec, admission, IdGenerator { UUID.randomUUID().toString() })
    }

    override fun close() = store.close()
}

internal fun withNodes(block: (TestSyncNode, TestSyncNode) -> Unit) {
    val a = Files.createTempFile("sync-a-", ".db")
    val b = Files.createTempFile("sync-b-", ".db")
    try {
        TestSyncNode("a", a).use { first -> TestSyncNode("b", b).use { second -> block(first, second) } }
    } finally {
        Files.deleteIfExists(a)
        Files.deleteIfExists(b)
    }
}

internal fun transportTo(remote: TestSyncNode): SyncTransport = object : SyncTransport {
    override fun exchange(peer: SotaId, request: SyncRequest): SyncResponse {
        require(peer == remote.id)
        val endpoint = SyncEndpoint(remote.service, messageCodec)
        return messageCodec.decodeResponse(endpoint.exchange(request.sender, messageCodec.encodeRequest(request)))
    }
}

internal fun record(
    id: String,
    origin: SotaId,
    assertion: StateAssertion? = null,
    parents: Set<EventId> = emptySet()
): SyncRecord {
    val actor = SubjectRef.Person(PersonId("person-${origin.value}"))
    val event = Event(
        EventId(id), "SYNC_FIXTURE", actor, syncTime, syncContext, "authority-chain-${origin.value}",
        null, mapOf("fact" to id), null, Provenance(null, actor, syncTime), "fixture-hash-$id", null
    )
    return SyncRecord(event, origin, parents, assertion)
}

internal fun claim(
    term: String,
    entity: SyncEntity = sharedAuthority,
    validity: Validity = Validity(syncTime, null),
    context: Context = syncContext
): StateAssertion = StateAssertion(entity, context, validity, mapOf("terms" to term))
