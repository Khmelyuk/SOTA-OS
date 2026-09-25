package sotaos.domain.agency

import sotaos.domain.shared.*
import java.time.Instant

data class Mission(
    val id: MissionId,
    val owner: SubjectRef,
    val intent: String,
    val objectives: List<String>,
    val participants: List<PersonId>,
    val mode: Mode,
    val state: LifecycleState
)

data class Task(
    val id: TaskId,
    val mission: MissionId,
    val description: String,
    val assignedTo: PersonId?,
    val state: LifecycleState
)

data class Decision(
    val id: DecisionId,
    val subject: SubjectRef,
    val intentRef: String,
    val optionsConsidered: List<String>,
    val chosenOption: String,
    val authorityRef: AuthorityId,
    val timestamp: Instant,
    val purpose: String = "Execute mission action",
    /** People whose person-specific interests/data are directly affected by this decision. */
    val affectedPersons: List<PersonId> = emptyList()
)

/**
 * ACTION: fact of execution. Every field here is required by
 * Architecture Core's traceability invariant (§41):
 * ACTOR -> IDENTITY -> AUTHORITY -> DECISION -> ACTION -> RESULT.
 *
 * There is NO constructor path that omits `authority` — an Action
 * without a valid Authority reference must be rejected by the
 * Application layer's ActionProtocol implementation (AC-15).
 */
data class Action(
    val id: ActionId,
    val actor: SubjectRef,
    val authority: AuthorityId,
    val decision: DecisionId?,
    val context: Context,
    val timestamp: Instant,
    val mode: Mode,
    val target: String?
)

/**
 * RESULT is deliberately NOT the same object as ACTION
 * (Data Model DM-10: ACTION != RESULT).
 */
data class Result(
    val id: ResultId,
    val action: ActionId,
    val description: String,
    val outcome: Outcome,
    val timestamp: Instant
)

enum class Outcome { SUCCESS, PARTIAL, FAILURE }

/**
 * ACCOUNTABILITY is modeled as a traced relation, not a standalone
 * profile object (Data Model §17).
 */
data class AccountabilityTrace(
    val decision: DecisionId?,
    val authority: AuthorityId,
    val action: ActionId,
    val responsibleSubject: SubjectRef
)

/**
 * P05 Mission/Action Protocol port.
 * `execute` MUST perform an authority check before producing an Action —
 * see architecture_invariants test "AC-15: no action without authority".
 */
interface MissionActionProtocol {
    fun createIntent(invocation: ProtocolInvocation, subject: SubjectRef, description: String): String
    fun formMission(invocation: ProtocolInvocation, owner: SubjectRef, intent: String, mode: Mode): Mission
    fun decide(
        invocation: ProtocolInvocation,
        subject: SubjectRef,
        mission: MissionId,
        authority: AuthorityId,
        chosen: String,
        affectedPersons: List<PersonId> = emptyList()
    ): Decision
    fun execute(invocation: ProtocolInvocation, decision: Decision): Action
    fun recordResult(invocation: ProtocolInvocation, action: Action, outcome: Outcome, description: String): Result
}
