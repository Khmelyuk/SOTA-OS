package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.memory.Evidence
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightExitInventoryRepository(private val db: SotaOsDatabase) : ExitInventoryRepository {
    override fun addRelation(relation: CoreExitRelation) {
        require(relation.kind in setOf("TRUST", "AGREEMENT"))
        require(relation.id.isNotBlank() && relation.description.isNotBlank())
        db.exitQueries.insertExitRelation(relation.id, relation.target.person.value, relation.target.core.value,
            relation.kind, relation.description)
    }
    override fun addObligation(obligation: ExitObligation) {
        require(obligation.state == ObligationState.OPEN && obligation.resolution == null)
        require(obligation.id.isNotBlank() && obligation.description.isNotBlank())
        db.exitQueries.insertObligation(obligation.id, obligation.target.person.value,
            obligation.target.core.value, obligation.description)
    }
    override fun obligations(target: ExitTarget): List<ExitObligation> = db.exitQueries
        .selectObligations(target.person.value, target.core.value).executeAsList().map {
            ExitObligation(it.obligation_id, target, it.description, ObligationState.valueOf(it.state), it.resolution)
        }
    override fun fulfillObligation(target: ExitTarget, id: String, evidence: String, at: Instant) {
        require(evidence.isNotBlank())
        require(obligations(target).any { it.id == id && it.state != ObligationState.FULFILLED })
        db.exitQueries.fulfillObligation(evidence, at.toString(), id, target.person.value, target.core.value)
    }
    override fun allowExport(target: ExitTarget, ref: ExitArtifactRef) {
        val approved = ExitArtifactMapping(db).approved(target, ref)
        db.exitQueries.insertExportGrant(target.person.value, target.core.value, ref.kind.name, ref.id, approved)
    }
    override fun saveEvidence(target: ExitTarget, evidence: Evidence) {
        require(evidence.id.value.isNotBlank())
        db.exitQueries.insertOwnedEvidence(evidence.id.value, target.person.value, target.core.value,
            evidence.describes, evidence.sourceEventId?.value, evidence.recordedAt.toString())
    }
    override fun auditGovernance(target: ExitTarget, operation: String, actor: SubjectRef,
        authority: AuthorityId, evidence: String, at: Instant) {
        val (kind, id) = ValueJsonMapping.subject(actor)
        db.exitQueries.insertExitGovernance(target.person.value, target.core.value, operation, kind, id,
            authority.value, evidence, at.toString())
    }
}
