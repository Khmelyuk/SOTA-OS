package sotaos.application.services

import sotaos.application.ports.Clock
import sotaos.application.ports.ExitPortableData
import sotaos.application.ports.ExitRepository
import sotaos.application.ports.ExitRequest
import sotaos.application.protocols.ExitExport
import sotaos.application.protocols.ExitProtocol
import sotaos.domain.shared.SubjectRef

class ExitService(
    private val exits: ExitRepository,
    private val clock: Clock
) : ExitProtocol {
    private data class Pending(val request: ExitRequest, val stages: MutableSet<Stage> = mutableSetOf())
    private enum class Stage { EXPORT_READY, REVOKE_DELEGATIONS, CLOSE_RELATIONS }
    private val pending = mutableMapOf<SubjectRef, Pending>()

    override fun requestExit(subject: SubjectRef, from: SubjectRef) {
        require(subject == from) { "Third-party exit requires an explicit authority-aware protocol invocation." }
        pending.getOrPut(subject) { Pending(ExitRequest(subject, from, clock.now())) }
    }

    override fun revokeActiveDelegations(subject: SubjectRef) {
        pending[subject]?.stages?.add(Stage.REVOKE_DELEGATIONS)
            ?: error("Exit must be requested before delegations are revoked.")
    }

    override fun closeRelations(subject: SubjectRef) {
        pending[subject]?.stages?.add(Stage.CLOSE_RELATIONS)
            ?: error("Exit must be requested before relations are closed.")
    }

    override fun exportPortableData(subject: SubjectRef): ExitExport {
        val exit = pending[subject] ?: error("Exit must be requested before export.")
        exit.stages.add(Stage.EXPORT_READY)
        return exits.snapshot(exit.request.subject).portable.toExport(subject)
    }

    override fun terminateParticipation(subject: SubjectRef, from: SubjectRef) {
        require(subject == from) { "Third-party termination requires an explicit authority-aware protocol invocation." }
        val exit = pending[subject] ?: error("Exit must be requested before termination.")
        require(exit.stages.containsAll(Stage.entries)) {
            "Exit must revoke delegations and close relations before termination."
        }
        exits.commitExit(exit.request)
        pending.remove(subject)
    }
}

private fun ExitPortableData.toExport(subject: SubjectRef): ExitExport = ExitExport(
    subject, identities, events, knowledgeContributed, evidenceOwned
)
