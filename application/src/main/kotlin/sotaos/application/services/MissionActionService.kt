package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.agency.*
import sotaos.domain.relation.Authority
import sotaos.domain.shared.*
import sotaos.domain.rights.ConsentProtocol
import java.security.MessageDigest
import sotaos.domain.memory.Event

class ActionNotAuthorizedException(message: String) : RuntimeException(message)

/**
 * Implements P05 Mission/Action Protocol.
 *
 * `execute()` is THE enforcement point for AC-15 / InvariantTestPlan #4
 * (NoActionWithoutAuthorityTest): it looks up the Authority referenced
 * by the Decision, validates it against the requested scope/context at
 * the current instant, and throws before any Action or Event is
 * persisted if the check fails. There is no other path in this codebase
 * that can create an Action.
 */
// The service explicitly injects each repository and policy port; keep this composition contract stable.
@Suppress("LongParameterList")
class MissionActionService(
    private val missions: MissionRepository,
    private val decisions: DecisionRepository,
    private val actions: ActionRepository,
    private val results: ResultRepository,
    private val authorities: AuthorityRepository,
    private val events: EventStore,
    private val authorityCheck: (Authority, Scope, java.time.Instant) -> Boolean,
    private val rightsConstraint: RightsConstraint,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val consent: ConsentProtocol? = null
) : MissionActionProtocol {

    override fun createIntent(invocation: ProtocolInvocation, subject: SubjectRef, description: String): String {
        require(invocation.actor == subject) { "Intent must be created by its subject." }
        rightsConstraint.check(invocation, "P05", "createIntent", subject)
        return description
    }

    override fun formMission(invocation: ProtocolInvocation, owner: SubjectRef, intent: String, mode: Mode): Mission {
        require(invocation.actor == owner) { "Mission must be formed by its owner." }
        rightsConstraint.check(invocation, "P05", "formMission", owner)
        val mission = Mission(
            id = MissionId(ids.next()),
            owner = owner,
            intent = intent,
            objectives = emptyList(),
            participants = emptyList(),
            mode = mode,
            state = LifecycleState.ACTIVE
        )
        return missions.save(mission)
    }

    override fun decide(
        invocation: ProtocolInvocation,
        subject: SubjectRef,
        mission: MissionId,
        authority: AuthorityId,
        chosen: String,
        affectedPersons: List<PersonId>
    ): Decision {
        require(invocation.actor == subject) { "Decision subject must match the invocation actor." }
        require(affectedPersons.distinct().size == affectedPersons.size) {
            "Affected Person list must not contain duplicates."
        }
        val requestedScope = personImpactScope(chosen, affectedPersons)
        val authorityForDecision = authorities.findById(authority)
            ?: throw ActionNotAuthorizedException("No such Authority ${authority.value}.")
        if (authorityForDecision.context.mission != null && authorityForDecision.context.mission != mission) {
            throw ActionNotAuthorizedException("Authority context does not cover mission ${mission.value}.")
        }
        if (!authorityCheck(authorityForDecision, requestedScope, clock.now())) {
            throw ActionNotAuthorizedException("Authority does not cover decision action $chosen.")
        }
        rightsConstraint.check(invocation, "P05", "decide", subject, authorityForDecision, requestedScope)
        val decision = Decision(
            id = DecisionId(ids.next()),
            subject = subject,
            intentRef = mission.value,
            optionsConsidered = listOf(chosen),
            chosenOption = chosen,
            authorityRef = authority,
            timestamp = clock.now(),
            purpose = invocation.purpose,
            affectedPersons = affectedPersons
        )
        return decisions.save(decision)
    }

    override fun execute(invocation: ProtocolInvocation, decision: Decision): Action {
        checkPersistedDecision(invocation, decision)
        val actor = invocation.actor
        val authority = authorities.findById(decision.authorityRef)
            ?: throw ActionNotAuthorizedException(
                "No such Authority ${decision.authorityRef.value} — Action rejected (AC-15)."
            )

        val missionId = MissionId(decision.intentRef)
        if (decision.subject != actor || authority.subject != actor) {
            throw ActionNotAuthorizedException(
                "Decision and Authority must both belong to the acting subject (AC-15)."
            )
        }
        if (authority.context.mission != null && authority.context.mission != missionId) {
            throw ActionNotAuthorizedException(
                "Authority context does not cover mission ${missionId.value} (AC-15)."
            )
        }

        val requestedScope = personImpactScope(decision.chosenOption, decision.affectedPersons)
        val ok = authorityCheck(authority, requestedScope, clock.now())
        if (!ok) {
            throw ActionNotAuthorizedException(
                "Authority ${authority.id.value} does not cover action " +
                    "'${decision.chosenOption}' at ${clock.now()} — Action rejected (AC-15)."
            )
        }

        // Protocol Architecture §16: rights/dignity is checked only after
        // identity, authority, and scope, and cannot be overridden by them.
        val rightsDecision = rightsConstraint.evaluate(
            RightsConstraintRequest(
                actor = actor,
                protocol = "P05",
                operation = decision.chosenOption,
                purpose = invocation.purpose,
                mission = missionId,
                authority = authority,
                context = authority.context,
                scope = requestedScope
            )
        )

        val affectedOthers = checkAffectedPersonsConsent(invocation, decision, authority)

        val action = Action(
            id = ActionId(ids.next()),
            actor = actor,
            authority = authority.id,
            decision = decision.id,
            context = authority.context,
            timestamp = clock.now(),
            mode = Mode.CREATION,
            target = null
        )
        val saved = actions.save(action)

        // Every Action produces an Event (CORE-16 / AC-14) — this is
        // not optional and not a separate call the caller can skip.
        recordActionEvent(saved, authority, rightsDecision.appliedPolicies, decision.affectedPersons, affectedOthers)

        return saved
    }

    private fun checkPersistedDecision(invocation: ProtocolInvocation, decision: Decision) {
        val storedDecision = decisions.findById(decision.id)
            ?: throw ActionNotAuthorizedException("Decision ${decision.id.value} is not persisted; execution rejected.")
        val rejection = when {
            storedDecision != decision -> "Decision data differs from its persisted record; execution rejected."
            decision.purpose != invocation.purpose ->
                "Execution purpose must match the purpose recorded in the Decision."
            else -> null
        }
        if (rejection != null) throw ActionNotAuthorizedException(rejection)
    }

    private fun checkAffectedPersonsConsent(
        invocation: ProtocolInvocation,
        decision: Decision,
        authority: Authority
    ): List<PersonId> {
        val actor = invocation.actor
        val affectedOthers = decision.affectedPersons.filterNot { person ->
            (actor as? SubjectRef.Person)?.id == person
        }
        if (affectedOthers.isNotEmpty()) {
            val consentProtocol = consent
                ?: throw ActionNotAuthorizedException(
                    "Consent service is unavailable; person-affecting action rejected."
                )
            affectedOthers.forEach { person ->
                val allowed = consentProtocol.permits(
                    subject = person,
                    recipient = actor,
                    purpose = invocation.purpose,
                    context = authority.context,
                    scope = Scope(
                        actions = setOf(decision.chosenOption),
                        resources = setOf("person:${person.value}")
                    ),
                    at = clock.now()
                )
                if (!allowed) {
                    throw ActionNotAuthorizedException(
                        "Consent from affected Person ${person.value} is missing, expired, revoked, " +
                            "or does not cover this purpose, context, action, and resource."
                    )
                }
            }
        }

        return affectedOthers
    }

    override fun recordResult(invocation: ProtocolInvocation, action: Action, outcome: Outcome,
        description: String): Result {
        require(invocation.actor == action.actor) { "Result author must match action actor." }
        rightsConstraint.check(invocation, "P05", "recordResult", authority = authorities.findById(action.authority))
        val result = Result(
            id = ResultId(ids.next()),
            action = action.id,
            description = description,
            outcome = outcome,
            timestamp = clock.now()
        )
        return results.save(result)
    }

    private fun recordActionEvent(
        action: Action,
        authority: Authority,
        appliedPolicies: Set<String>,
        affectedPersons: List<PersonId>,
        consentCheckedPersons: List<PersonId>
    ) {
        val payload = mapOf(
            "actionId" to action.id.value,
            "authorityId" to authority.id.value,
            "decisionId" to action.decision?.value,
            "rightsPolicies" to appliedPolicies.toList(),
            "affectedPersons" to affectedPersons.map { it.value },
            "consentCheckedPersons" to consentCheckedPersons.map { it.value }
        )
        val provenance = Provenance(
            sourceEventId = null,
            author = action.actor,
            recordedAt = action.timestamp
        )
        val canonical = "${action.id.value}|${action.actor}|${authority.id.value}|${action.timestamp}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }

        events.append(
            Event(
                id = EventId(ids.next()),
                type = "ACTION_EXECUTED",
                actor = action.actor,
                timestamp = action.timestamp,
                context = action.context,
                authorityRef = authority.id.value,
                decisionRef = action.decision?.value,
                payload = payload,
                resultRef = null,
                provenance = provenance,
                contentHash = hash,
                signature = null // R0/R1 in MVP per ADR-003; signing added at R2+
            )
        )
    }
}

private fun personImpactScope(action: String, persons: List<PersonId>): Scope = Scope(
    actions = setOf(action),
    resources = persons.mapTo(linkedSetOf()) { "person:${it.value}" }
)
