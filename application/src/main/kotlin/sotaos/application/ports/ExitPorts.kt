package sotaos.application.ports

import sotaos.domain.collective.Membership
import sotaos.domain.identity.Identity
import sotaos.domain.memory.*
import sotaos.domain.relation.*
import sotaos.domain.shared.SubjectRef
import java.time.Instant

data class ExitRequest(val subject: SubjectRef, val requestedBy: SubjectRef, val requestedAt: Instant)

data class ExitPortableData(
    val identities: List<Identity>, val events: List<Event>,
    val knowledgeContributed: List<Knowledge>, val evidenceOwned: List<Evidence>
)

data class ExitSnapshot(
    val memberships: List<Membership>, val authorities: List<Authority>,
    val trusts: List<Trust>, val competences: List<Competence>,
    val agreements: List<Agreement>, val portable: ExitPortableData
)

data class ExitReceipt(
    val subject: SubjectRef, val requestedBy: SubjectRef,
    val completedAt: Instant, val transitionId: String
) { init { require(transitionId.isNotBlank()) } }

interface ExitRepository {
    fun snapshot(subject: SubjectRef): ExitSnapshot
    fun commitExit(request: ExitRequest): ExitReceipt
}
