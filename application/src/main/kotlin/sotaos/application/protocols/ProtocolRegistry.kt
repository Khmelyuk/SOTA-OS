package sotaos.application.protocols

import sotaos.domain.identity.IdentityProtocol
import sotaos.domain.collective.MembershipProtocol
import sotaos.domain.relation.AuthorityProtocol
import sotaos.domain.agency.MissionActionProtocol
import sotaos.domain.agency.AgentRegistryProtocol
import sotaos.domain.memory.MemoryKnowledgeProtocol
import sotaos.domain.rights.ConsentProtocol

/**
 * Single composition point for all MVP protocols (P01-P10 minus P11
 * Federation, P12 full Agent execution — see ADR-006 / MVP scope).
 *
 * This interface exists so that:
 *   1. No Infrastructure/API code can reach the Domain layer except
 *      through these named protocol ports (traceable per Master Prompt
 *      §16).
 *   2. Cross-cutting checks (Rights/Dignity constraint, per Protocol
 *      Architecture §16) have an application port. P05 action execution
 *      currently requires this check; extending guarded invocation
 *      context to every command port remains follow-up work.
 *
 * P09 Sync and P10 Exit are intentionally NOT domain-only contracts —
 * they require Infrastructure (transport, storage) and are declared
 * separately through application/sync ports, sync/SyncService, and ExitProtocol below.
 */
interface ProtocolRegistry {
    val identity: IdentityProtocol          // P01
    // P02 Trust + P03 Competence + P04 Authority/Delegation share one port:
    val authority: AuthorityProtocol        // P02 + P03 + P04
    val membership: MembershipProtocol      // P08 (membership subset)
    val missionAction: MissionActionProtocol // P05
    val memoryKnowledge: MemoryKnowledgeProtocol // P06 + P07
    val agentRegistry: AgentRegistryProtocol // P12 (registration-only, ADR-006)
    val consent: ConsentProtocol              // Rights / Consent constraint protocol
}

/**
 * P10 Exit Protocol — kept as its own service (not folded into
 * MembershipProtocol) because Exit spans Identity + Collective +
 * Relation + Memory layers simultaneously (Protocol Architecture §13,
 * §29).
 */
interface ExitProtocol {
    fun requestExit(invocation: sotaos.domain.shared.ProtocolInvocation,
        core: sotaos.domain.shared.CoreId): sotaos.application.ports.ExitProcess
    fun revokeActiveDelegations(invocation: sotaos.domain.shared.ProtocolInvocation,
        exitId: String): sotaos.application.ports.ExitProcess
    fun closeRelations(invocation: sotaos.domain.shared.ProtocolInvocation,
        exitId: String): sotaos.application.ports.ExitProcess
    fun settleObligations(invocation: sotaos.domain.shared.ProtocolInvocation,
        exitId: String): sotaos.application.ports.ExitProcess
    fun exportPortableData(invocation: sotaos.domain.shared.ProtocolInvocation,
        exitId: String): sotaos.application.ports.ExitDocument
    fun terminateParticipation(invocation: sotaos.domain.shared.ProtocolInvocation,
        exitId: String, exportedSha256: String): sotaos.application.ports.ExitReceipt
}
