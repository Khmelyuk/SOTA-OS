package sotaos.api.exit

import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.*
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.*

/** Opt-in host composition. Private keys remain in the host's actor-bound signer provider. */
class SignedP10Runtime(
    store: SqlDelightStore,
    local: LocalSyncIdentity,
    governanceContext: Context,
    clock: Clock,
    ids: IdGenerator,
    signers: (SubjectRef) -> EventSigner,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) {
    val sync = P09Runtime(store, local, governanceContext, clock, ids, rights)
    private val records = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())
    private val keys = SqlDelightRepositories(store.database).actorSigningKeys
    private val recorder = ExitEventRecorder { event, parents ->
        require(event.actor in local.actors && event.actor is SubjectRef.Person)
        val known = records.records().associateBy { it.event.id }
        parents.forEach { id ->
            val parent = requireNotNull(known[id]) { "Cannot convert legacy exit history to signed history." }
            require(parent.origin == local.node && parent.event.signature != null) {
                "Resume with the original signed exit origin."
            }
        }
        val verifier = requireNotNull(ActorKeyDirectory.fromRecords(keys.findAll()).signerFor(event.actor)) {
            "Provision the actor public key before starting a signed exit."
        }
        val record = sync.integrity.sign(SyncRecord(event, local.node, parents), signers(event.actor))
        require(verifier.verify(record.event.contentHash, requireNotNull(record.event.signature))) {
            "Exit signer does not match the current actor key."
        }
        // Nested transaction uses the same SQLite database as the exit mutation and audit.
        sync.recordLocal(record)
    }
    val exit = P10Runtime(store, clock, ids, rights, recorder)
}
