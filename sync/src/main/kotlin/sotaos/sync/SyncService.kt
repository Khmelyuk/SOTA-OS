package sotaos.sync

import sotaos.application.ports.IdGenerator
import sotaos.application.sync.*
import sotaos.domain.shared.SotaId
import sotaos.domain.sync.*

/** Durable, retryable P09 exchange. No transport call is needed for local recording. */
class SyncService(
    private val node: SotaId,
    private val repository: SyncRepository,
    private val codec: SyncRecordCodec,
    private val admission: SyncAdmission,
    private val ids: IdGenerator
) {
    private val merge = DeterministicMerge()

    fun recordLocal(record: SyncRecord) = repository.transaction {
        require(record.origin == node) { "Local records must identify this node as origin." }
        appendAndMerge(listOf(record))
    }

    /** Existing Core Loop events are additive; their missing entity claims are not invented. */
    fun captureLocalEvents() = repository.transaction {
        val pending = repository.untrackedEvents().map { event ->
            SyncRecord(event, node, setOfNotNull(event.correctsEventId))
        }
        appendAndMerge(pending)
    }

    fun synchronize(peer: SotaId, transport: SyncTransport): PeerCheckpoint {
        require(peer != node) { "A node cannot synchronize with itself." }
        captureLocalEvents()
        val request = repository.transaction {
            val checkpoint = repository.checkpoint(peer)
            SyncRequest(ids.next(), node, checkpoint.received, repository.batch(checkpoint.sent))
        }
        admission.check(peer, SyncDirection.EXPORT, request.batch.records)
        val response = transport.exchange(peer, request)
        require(response.sender == peer && response.requestId == request.id) { "Unexpected sync response." }
        require(response.acceptedThrough == request.batch.through) { "Incomplete acknowledgement." }
        require(response.batch.after == request.receivedThrough) { "Unexpected remote cursor." }
        admission.check(peer, SyncDirection.IMPORT, response.batch.records)
        return repository.transaction {
            val checkpoint = repository.checkpoint(peer)
            require(checkpoint.received == request.receivedThrough && checkpoint.sent == request.batch.after) {
                "Concurrent peer exchange; retry with the current checkpoint."
            }
            appendAndMerge(response.batch.records)
            val next = PeerCheckpoint(response.acceptedThrough, response.batch.through)
            repository.saveCheckpoint(peer, next)
            next
        }
    }

    /** The adapter supplies the authenticated peer; sender text is never authentication evidence. */
    fun receive(authenticatedPeer: SotaId, request: SyncRequest): SyncResponse {
        require(request.sender == authenticatedPeer && request.sender != node) { "Unexpected peer identity." }
        admission.check(authenticatedPeer, SyncDirection.IMPORT, request.batch.records)
        return repository.transaction {
            captureLocalEvents()
            val checkpoint = repository.checkpoint(authenticatedPeer)
            require(request.batch.after <= checkpoint.received) { "Remote journal has a gap." }
            checkReplayPrefix(request.batch, checkpoint.received)
            appendAndMerge(request.batch.records)
            val response = SyncResponse(
                request.id, node, request.batch.through, repository.batch(request.receivedThrough)
            )
            admission.check(authenticatedPeer, SyncDirection.EXPORT, response.batch.records)
            repository.saveCheckpoint(authenticatedPeer, checkpoint.copy(
                received = maxOf(checkpoint.received, request.batch.through)
            ))
            response
        }
    }

    fun projection(entity: SyncEntity): SyncProjection = repository.transaction {
        merge.project(entity, repository.records(), repository.conflicts())
    }

    private fun checkReplayPrefix(batch: SyncBatch, received: Long) {
        val known = repository.records().associateBy { it.event.id }
        val repeated = minOf(received - batch.after, batch.records.size.toLong()).toInt()
        batch.records.take(repeated).forEach { record ->
            require(known[record.event.id]?.let(codec::encode) == codec.encode(record)) {
                "A replay changed an already acknowledged event."
            }
        }
    }

    private fun appendAndMerge(incoming: List<SyncRecord>) {
        val known = repository.records().associateBy { it.event.id }.toMutableMap()
        incoming.forEach { record ->
            val previous = known.putIfAbsent(record.event.id, record)
            require(previous == null || codec.encode(previous) == codec.encode(record)) {
                "Event identity reused with different content or sync metadata: ${record.event.id.value}."
            }
        }
        val ordered = merge.ordered(known.values)
        val existing = repository.records().mapTo(mutableSetOf()) { it.event.id }
        ordered.filter { it.event.id !in existing }.forEach(repository::append)
        merge.conflicts(ordered).forEach(repository::saveConflict)
    }
}
