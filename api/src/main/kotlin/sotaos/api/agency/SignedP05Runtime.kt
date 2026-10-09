package sotaos.api.agency

import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.agency.*
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.security.*

/** Host authenticates invocation.actor; provision public keys separately through governed P09 operations. */
class SignedP05Runtime(
    private val store: SqlDelightStore,
    local: LocalSyncIdentity,
    governanceContext: Context,
    clock: Clock,
    ids: IdGenerator,
    signers: (SubjectRef) -> EventSigner,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) : MissionActionProtocol {
    val sync = P09Runtime(store, local, governanceContext, clock, ids, rights)
    private val repositories = SqlDelightRepositories(store.database)
    private val events = object : EventStore by repositories.events {
        override fun append(event: Event): Event {
            require(event.type == "ACTION_EXECUTED" && event.signature == null)
            require(event.actor in local.actors && event.actor !is SubjectRef.Agent)
            val verifier = requireNotNull(ActorKeyDirectory.fromRecords(repositories.actorSigningKeys.findAll())
                .signerFor(event.actor)) { "Provision the actor public key before executing a signed action." }
            val record = sync.integrity.sign(SyncRecord(event, local.node), signers(event.actor))
            require(verifier.verify(record.event.contentHash, requireNotNull(record.event.signature))) {
                "Action signer does not match the current actor key."
            }
            // Same database and outer P05 transaction: action, event, journal and receipt commit together.
            sync.recordLocal(record)
            return record.event
        }
    }
    private val service = MissionActionService(
        repositories.missions, repositories.decisions, repositories.actions, repositories.results,
        repositories.authorities, events, AuthorityLineage(repositories.authorities)::isValid,
        rights, clock, ids, ConsentService(repositories.consents, rights, clock, ids)
    )

    override fun createIntent(invocation: ProtocolInvocation, subject: SubjectRef, description: String): String =
        store.withLocalAccess { service.createIntent(invocation, subject, description) }

    override fun formMission(invocation: ProtocolInvocation, owner: SubjectRef, intent: String, mode: Mode): Mission =
        store.withLocalAccess { service.formMission(invocation, owner, intent, mode) }

    override fun decide(
        invocation: ProtocolInvocation,
        subject: SubjectRef,
        mission: MissionId,
        authority: AuthorityId,
        chosen: String,
        affectedPersons: List<PersonId>
    ): Decision = store.withLocalAccess {
        service.decide(invocation, subject, mission, authority, chosen, affectedPersons)
    }

    override fun execute(invocation: ProtocolInvocation, decision: Decision): Action =
        store.withLocalAccess { service.execute(invocation, decision) }

    override fun recordResult(invocation: ProtocolInvocation, action: Action, outcome: Outcome,
        description: String): Result = store.withLocalAccess {
        service.recordResult(invocation, action, outcome, description)
    }
}
