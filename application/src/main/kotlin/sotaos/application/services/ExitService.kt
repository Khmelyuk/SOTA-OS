package sotaos.application.services

import sotaos.application.ports.*
import sotaos.application.protocols.ExitProtocol
import sotaos.domain.shared.*

/** Durable, restartable voluntary exit. Each completed stage is an atomic, audited transition. */
class ExitService(
    private val exits: ExitRepository,
    private val scope: ExitScopeRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val rights: RightsConstraint
) : ExitProtocol {
    override fun requestExit(invocation: ProtocolInvocation, core: CoreId): ExitProcess = exits.transaction {
        val person = requireNotNull(invocation.actor as? SubjectRef.Person) { "Only a Person may request self-exit." }
        val target = ExitTarget(person.id, core)
        authorize(invocation, target, "requestExit")
        exits.findOpen(target) ?: run {
            require(scope.hasMembership(target)) { "No active membership in the requested Core." }
            val now = clock.now()
            val process = ExitProcess(ids.next(), target, now, now, ExitStage.REQUESTED)
            exits.save(process)
            exits.appendTransition(ExitTransition(process.id, process.stage, person.id, now))
            process
        }
    }

    override fun revokeActiveDelegations(invocation: ProtocolInvocation, exitId: String): ExitProcess =
        advance(invocation, exitId, ExitStage.REQUESTED, ExitStage.DELEGATIONS_REVOKED) {
            scope.revokeDelegations(it.target)
        }

    override fun closeRelations(invocation: ProtocolInvocation, exitId: String): ExitProcess =
        advance(invocation, exitId, ExitStage.DELEGATIONS_REVOKED, ExitStage.RELATIONS_CLOSED) {
            scope.closeRelations(it.target)
        }

    override fun settleObligations(invocation: ProtocolInvocation, exitId: String): ExitProcess =
        advance(invocation, exitId, ExitStage.RELATIONS_CLOSED, ExitStage.OBLIGATIONS_SETTLED) {
            // Retention is NOT discharge: responsibility survives membership termination.
            scope.retainObligations(it.target, invocation.purpose, clock.now())
        }

    override fun exportPortableData(invocation: ProtocolInvocation, exitId: String): ExitDocument = exits.transaction {
        val process = advance(invocation, exitId, ExitStage.OBLIGATIONS_SETTLED, ExitStage.EXPORT_READY) {
            exits.prepareExport(it)
        }
        requireNotNull(exits.export(process.id)) { "Exit export is missing." }
    }

    override fun terminateParticipation(invocation: ProtocolInvocation, exitId: String,
        exportedSha256: String): ExitReceipt = exits.transaction {
        val before = load(invocation, exitId, "terminateParticipation")
        val document = requireNotNull(exits.export(exitId)) { "Export must succeed before termination." }
        require(exportedSha256 == document.sha256) { "The delivered export must match the durable archive." }
        val process = advance(invocation, before.id, ExitStage.EXPORT_READY, ExitStage.COMPLETED) {
            require(!scope.hasDelegations(it.target) && !scope.hasRelations(it.target))
            require(!scope.hasUnresolvedObligations(it.target))
            scope.terminateMembership(it.target, clock.now())
        }
        ExitReceipt(process.id, process.target, process.updatedAt, document.sha256)
    }

    private fun advance(invocation: ProtocolInvocation, id: String, expected: ExitStage,
        next: ExitStage, mutate: (ExitProcess) -> Unit): ExitProcess = exits.transaction {
        val process = load(invocation, id, next.name)
        if (process.stage.ordinal >= next.ordinal) return@transaction process
        require(process.stage == expected) { "Exit stage ${process.stage} cannot advance to $next." }
        mutate(process)
        val updated = process.copy(stage = next, updatedAt = clock.now())
        exits.save(updated)
        exits.appendTransition(ExitTransition(id, next, process.target.person, updated.updatedAt))
        updated
    }

    private fun load(invocation: ProtocolInvocation, id: String, operation: String): ExitProcess {
        val process = requireNotNull(exits.find(id)) { "Unknown exit." }
        authorize(invocation, process.target, operation)
        return process
    }

    private fun authorize(invocation: ProtocolInvocation, target: ExitTarget, operation: String) {
        require(invocation.actor == SubjectRef.Person(target.person)) { "Only the exiting Person may advance exit." }
        rights.check(invocation, "P10", operation, SubjectRef.Core(target.core),
            scope = Scope(setOf(operation), setOf("core:${target.core.value}")))
    }
}
