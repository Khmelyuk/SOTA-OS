package sotaos.persistence

import sotaos.application.ports.EventStore
import sotaos.application.sync.*
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.*
import sotaos.persistence.db.SotaOsDatabase

/** Metadata and event rows commit together. Codec injection preserves ADR-007 module boundaries. */
class SqlDelightSyncRepository(
    private val db: SotaOsDatabase,
    private val codec: SyncRecordCodec,
    private val events: EventStore = SqlDelightEventStore(db)
) : SyncRepository {
    override fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    override fun records(): List<SyncRecord> = db.syncQueries.allRecords().executeAsList().map(codec::decode)

    override fun append(record: SyncRecord) {
        val existing = events.findById(record.event.id)
        require(existing == null || codec.encode(record.copy(event = existing)) == codec.encode(record)) {
            "Sync cannot replace an existing event."
        }
        if (existing == null) events.append(record.event)
        db.syncQueries.appendRecord(record.event.id.value, codec.encode(record))
    }

    override fun batch(after: Long): SyncBatch {
        require(after in 0..db.syncQueries.lastPosition().executeAsOne()) { "Cursor outside local journal." }
        val records = db.syncQueries.recordsAfter(after, MAX_SYNC_BATCH.toLong()).executeAsList().map(codec::decode)
        return SyncBatch(after, after + records.size, records)
    }

    override fun checkpoint(peer: SotaId): PeerCheckpoint = db.syncQueries.checkpoint(peer.value)
        .executeAsOneOrNull()?.let { PeerCheckpoint(it.sent, it.received) } ?: PeerCheckpoint()

    override fun saveCheckpoint(peer: SotaId, checkpoint: PeerCheckpoint) {
        require(checkpoint.sent >= 0 && checkpoint.received >= 0)
        db.syncQueries.saveCheckpoint(peer.value, checkpoint.sent, checkpoint.received)
    }

    override fun saveConflict(conflict: SyncConflict) {
        db.syncQueries.saveConflict(conflict.entity.kind.name, conflict.entity.id,
            conflict.left.value, conflict.right.value)
    }

    override fun conflicts(): List<SyncConflict> = db.syncQueries.allConflicts().executeAsList().map {
        SyncConflict(SyncEntity(SyncEntityKind.valueOf(it.entity_kind), it.entity_id),
            EventId(it.left_event_id), EventId(it.right_event_id))
    }

    override fun untrackedEvents(): List<Event> = db.syncQueries.untrackedEvents().executeAsList().map {
        checkNotNull(events.findById(EventId(it)))
    }
}
