package sotaos.api.memory

import sotaos.api.sync.P09Runtime
import sotaos.application.ports.*
import sotaos.application.services.MemoryService
import sotaos.domain.memory.*
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.persistence.SqlDelightStore
import sotaos.protocol.JsonMemorySnapshotCodec
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
    private val service = MemoryService(repositories.experiences, repositories.knowledge, clock, ids, rights)
    private val sources = SignedMemorySources(store, local, clock)
    private val journal = SignedMemoryJournal(store, local, sync, clock, ids, signers)
    private val snapshots = JsonMemorySnapshotCodec()

    override fun createExperience(invocation: ProtocolInvocation, fromEvents: List<Event>, conclusion: String,
        applicability: String): Experience = transaction(invocation) {
        val parents = sources.events(fromEvents)
        val experience = service.createExperience(invocation, parents, conclusion, applicability)
        journal.append(invocation, MemoryFact("EXPERIENCE_RECORDED", experience.id.value,
            snapshots.encode(experience)), parents)
        experience
    }

    override fun proposeKnowledgeCandidate(invocation: ProtocolInvocation, from: Experience,
        statement: String): Knowledge = transaction(invocation) {
        val parent = sources.experience(from)
        val knowledge = service.proposeKnowledgeCandidate(invocation, from, statement)
        journal.append(invocation, MemoryFact("KNOWLEDGE_PROPOSED", knowledge.id.value,
            snapshots.encode(knowledge)), listOf(parent))
        knowledge
    }

    override fun validateKnowledge(invocation: ProtocolInvocation, candidate: Knowledge,
        validator: SubjectRef): Knowledge = transaction(invocation) {
        val parent = sources.knowledge(candidate)
        require(candidate.status == KnowledgeStatus.CANDIDATE) { "Only a current candidate may be validated." }
        val knowledge = service.validateKnowledge(invocation, candidate, validator)
        journal.append(invocation, MemoryFact("KNOWLEDGE_VALIDATED", knowledge.id.value,
            snapshots.encode(knowledge)), listOf(parent))
        knowledge
    }

    override fun deprecate(invocation: ProtocolInvocation, knowledge: Knowledge, reason: String): Knowledge =
        transaction(invocation) {
            val parent = sources.knowledge(knowledge)
            require(reason.isNotBlank()) { "Deprecation requires an explicit reason." }
            require(knowledge.status in setOf(KnowledgeStatus.CANDIDATE, KnowledgeStatus.VALIDATED))
            val deprecated = service.deprecate(invocation, knowledge, reason)
            journal.append(invocation, MemoryFact("KNOWLEDGE_DEPRECATED", deprecated.id.value,
                snapshots.encode(deprecated)), listOf(parent), reason)
            deprecated
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
