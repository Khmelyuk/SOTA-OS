package sotaos.security

import sotaos.application.ports.*
import sotaos.application.sync.*
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.domain.sync.SyncEntity

data class LocalSyncIdentity(
    val node: SotaId,
    val actors: Set<SubjectRef>,
    val assertionEntities: Set<SyncEntity> = emptySet()
)

/** Deny by default. Every record must be signed, including records with no registered actor key. */
class ProductionPeerAdmission(
    private val peers: PeerTrustRepository,
    private val keys: ActorSigningKeyRepository,
    private val local: LocalSyncIdentity,
    private val integrity: SyncRecordIntegrity
) : SyncAdmission {
    override fun check(peer: SotaId, direction: SyncDirection, records: List<SyncRecord>) = peers.transaction {
        val trusted = requireNotNull(peers.find(peer)?.takeIf { it.active }) { "Untrusted peer." }
        require(peer != local.node && direction in trusted.policy.directions)
        require(records.size <= MAX_SYNC_BATCH)
        val directory = ActorKeyDirectory.fromRecords(keys.findAll())
        val policy = SyncAdmission { _, checkedDirection, admitted ->
            admitted.forEach { record -> checkRecord(trusted.policy, checkedDirection, record, directory) }
        }
        EventSignatureAdmission(policy, directory).check(peer, direction, records)
    }

    private fun checkRecord(policy: PeerTrustPolicy, direction: SyncDirection,
        record: SyncRecord, directory: ActorKeyDirectory) {
        val origins = if (direction == SyncDirection.IMPORT) policy.importOrigins else policy.exportOrigins
        require(record.origin in origins && record.event.context in policy.contexts) { "Sharing scope denied." }
        val origin = if (record.origin == local.node) null else {
            requireNotNull(peers.find(record.origin)?.takeIf { it.active }) { "Untrusted origin." }.policy
        }
        val actors = if (origin == null) local.actors else {
            require(record.event.context in origin.contexts) { "Origin context denied." }
            origin.actors
        }
        val event = record.event
        require(event.actor in actors && event.actor !is SubjectRef.Agent) { "Actor is not bound to this origin." }
        require(event.provenance.author == event.actor && event.provenance.recordedAt == event.timestamp)
        require(event.provenance.sourceEventId == null || event.provenance.sourceEventId in record.parents)
        record.assertion?.let { assertion ->
            require(assertion.context == event.context && assertion.entity in policy.assertionEntities)
            require(assertion.entity in (origin?.assertionEntities ?: local.assertionEntities))
        }
        require(event.signature != null && directory.signerFor(event.actor) != null) { "Trusted signature required." }
        integrity.check(record)
    }
}
