package sotaos.api.memory

import sotaos.application.ports.Clock
import sotaos.domain.memory.Event
import sotaos.domain.memory.Experience
import sotaos.domain.memory.Knowledge
import sotaos.domain.shared.SubjectRef
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.persistence.SqlDelightVerifiedRecordRepository
import sotaos.protocol.JsonMemorySnapshotCodec
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.Ed25519EventSigner
import sotaos.security.LocalSyncIdentity
import sotaos.security.SyncRecordIntegrity

/** Source checks run inside the producer's shared store gate and transaction. */
internal class SignedMemorySources(
    store: SqlDelightStore,
    private val local: LocalSyncIdentity,
    private val clock: Clock
) {
    private val repositories = SqlDelightRepositories(store.database)
    private val codec = JsonSyncRecordCodec()
    private val snapshots = JsonMemorySnapshotCodec()
    private val journal = SqlDelightSyncRepository(store.database, codec)
    private val proofs = SqlDelightVerifiedRecordRepository(store.database)

    fun events(events: List<Event>): List<Event> {
        require(events.isNotEmpty() && events.map { it.id }.distinct().size == events.size) {
            "Experience requires distinct source Events."
        }
        require(events.all { it.context == events.first().context }) { "Source contexts must match." }
        return events.map { event ->
            require(repositories.events.findById(event.id) == event) { "Source Event differs from storage." }
            verified(requireNotNull(journal.records().singleOrNull { it.event.id == event.id }))
        }
    }

    fun experience(value: Experience): Event {
        require(repositories.experiences.findById(value.id) == value) { "Source Experience differs from storage." }
        return snapshot(setOf("EXPERIENCE_RECORDED"), value.id.value, snapshots.encode(value))
    }

    fun knowledge(value: Knowledge): Event {
        require(repositories.knowledge.findById(value.id) == value) { "Knowledge differs from its current record." }
        return snapshot(setOf("KNOWLEDGE_PROPOSED", "KNOWLEDGE_VALIDATED", "KNOWLEDGE_DEPRECATED"),
            value.id.value, snapshots.encode(value))
    }

    private fun snapshot(types: Set<String>, id: String, snapshot: String): Event = verified(
        requireNotNull(journal.records().singleOrNull {
            it.event.type in types && it.event.payload["entityId"] == id &&
                it.event.payload["snapshot"] == snapshot
        }) { "An exact signed local P07 source snapshot is required; legacy data cannot be upgraded." }
    )

    private fun verified(record: SyncRecord): Event {
        val event = record.event
        require(record.origin == local.node && event.actor in local.actors && event.actor !is SubjectRef.Agent)
        require(repositories.events.findById(event.id) == event && !event.timestamp.isAfter(clock.now()))
        SyncRecordIntegrity(codec).check(record)
        val proof = requireNotNull(proofs.find(event.id)) { "Source requires exact local verification evidence." }
        require(proof.canonicalRecord == codec.encode(record) && proof.key.actor == event.actor)
        require(Ed25519EventSigner.fromPublicKeyEncoded(proof.key.publicKeyBase64)
            .verify(event.contentHash, requireNotNull(event.signature)))
        return event
    }
}
