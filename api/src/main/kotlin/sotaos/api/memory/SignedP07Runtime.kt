package sotaos.api.memory

import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.services.MemoryService
import sotaos.domain.memory.*
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.security.*

/** Opt-in local P07 producer. Host authenticates invocation.actor and protects provisioned signing keys. */
class SignedP07Runtime(
    private val store: SqlDelightStore,
    private val local: LocalSyncIdentity,
    governanceContext: Context,
    clock: Clock,
    ids: IdGenerator,
    signers: (SubjectRef) -> EventSigner,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) : MemoryKnowledgeProtocol {
    val sync = P09Runtime(store, local, governanceContext, clock, ids, rights)
    private val repositories = SqlDelightRepositories(store.database)
    private val sources = SignedMemorySources(store, local, clock)
    private val service = MemoryService(repositories.experiences, repositories.knowledge, clock, ids, rights,
        repositories.events, SignedMemoryAudit(sources,
            SignedMemoryJournal(store, local, sync, clock, ids, signers)))

    override fun createExperience(invocation: ProtocolInvocation, fromEvents: List<Event>, conclusion: String,
        applicability: String): Experience = transaction(invocation) {
        service.createExperience(invocation, sources.events(fromEvents), conclusion, applicability)
    }

    override fun proposeKnowledgeCandidate(invocation: ProtocolInvocation, from: Experience,
        statement: String): Knowledge = transaction(invocation) {
        sources.experience(from)
        service.proposeKnowledgeCandidate(invocation, from, statement)
    }

    override fun validateKnowledge(invocation: ProtocolInvocation, candidate: Knowledge,
        validator: SubjectRef): Knowledge = transaction(invocation) {
        sources.knowledge(candidate)
        service.validateKnowledge(invocation, candidate, validator)
    }

    override fun deprecate(invocation: ProtocolInvocation, knowledge: Knowledge, reason: String): Knowledge =
        transaction(invocation) {
            sources.knowledge(knowledge)
            service.deprecate(invocation, knowledge, reason)
        }

    override fun recordEvent(invocation: ProtocolInvocation, type: String, actor: SubjectRef, context: Context,
        payload: Map<String, Any?>, authorityRef: String?): Event = transaction(invocation) {
        service.recordEvent(invocation, type, actor, context, payload, authorityRef)
    }

    private fun <T> transaction(invocation: ProtocolInvocation, block: () -> T): T = store.withLocalAccess {
        store.database.transactionWithResult {
            require(invocation.actor in local.actors && invocation.actor !is SubjectRef.Agent) {
                "Signed P07 requires a local non-Agent actor."
            }
            block()
        }
    }
}
