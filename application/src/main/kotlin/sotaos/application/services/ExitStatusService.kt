package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.shared.*

/** The host authenticates the actor; this query cannot select a different Person. */
class ExitStatusService(
    private val exits: ExitRepository,
    private val scope: ExitScopeRepository,
    private val rights: RightsConstraint
) {
    fun status(invocation: ProtocolInvocation, core: CoreId): ExitStatus = exits.transaction {
        val person = requireNotNull(invocation.actor as? SubjectRef.Person) { "Only a Person may inspect self-exit." }
        val target = ExitTarget(person.id, core)
        rights.check(invocation, "P10", "status", SubjectRef.Core(core),
            scope = Scope(setOf("status"), setOf("core:${core.value}")))
        val history = exits.findByTarget(target)
            .sortedWith(compareBy<ExitProcess> { it.requestedAt }.thenBy { it.id })
            .map { process -> ExitProgress(process, exits.export(process.id)?.sha256) }
        ExitStatus(target, scope.hasMembership(target), history)
    }
}
