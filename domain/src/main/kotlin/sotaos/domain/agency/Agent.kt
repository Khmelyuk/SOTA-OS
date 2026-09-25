package sotaos.domain.agency

import sotaos.domain.shared.*

/**
 * AGENT stub — per ADR-006.
 *
 * THIS TYPE HAS NO EXECUTION CAPABILITY IN MVP.
 * There is deliberately no `execute()`, `act()`, or `delegate()` method
 * on this class or in any Application-layer service that accepts an
 * Agent as the acting SubjectRef for MissionActionProtocol.execute()
 * or AuthorityProtocol.grant() (issuer position).
 *
 * MODEL != AGENT (Agent/AI Architecture §5): `modelRef` is a free-text
 * pointer to whatever inference component may be wired in post-MVP; it
 * carries no authority of its own.
 */
data class Agent(
    val id: AgentId,
    val ownerPrincipal: SubjectRef,   // must be Person, Core, or Sota — never Agent
    val purpose: String,
    val declaredCapabilities: Set<String>, // descriptive only, not executable grants
    val constraints: Set<String>,
    val modelRef: String?,
    val state: LifecycleState
) {
    init {
        require(ownerPrincipal !is SubjectRef.Agent) {
            "AI-01/AI-02 violation: an Agent cannot be owned by another Agent " +
                "(no self-delegation, no agent-originated principal chains in MVP)."
        }
    }
}

/**
 * Deliberately minimal — read-only registration/lifecycle only.
 * No AgentActionProtocol exists in this codebase for MVP (see MVP Spec
 * §13, Agent/AI Architecture §10 Level 0/1 only).
 */
interface AgentRegistryProtocol {
    fun register(invocation: ProtocolInvocation, owner: SubjectRef, purpose: String,
        declaredCapabilities: Set<String>): Agent
    fun suspend(invocation: ProtocolInvocation, agent: Agent, reason: String): Agent
    fun revoke(invocation: ProtocolInvocation, agent: Agent, reason: String): Agent
}
