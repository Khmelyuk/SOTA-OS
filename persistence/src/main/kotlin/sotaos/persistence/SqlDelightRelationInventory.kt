package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.relation.Agreement
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.relation.Trust
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase

/** All terms, exit inventory and obligations are inserted atomically into the same local database. */
class SqlDelightRelationInventory(private val db: SotaOsDatabase) : RelationInventory {
    private val inventory = SqlDelightExitInventoryRepository(db)

    override fun registerTrust(id: String, target: ExitTarget, trust: Trust) {
        require(trust.subject == SubjectRef.Person(target.person)) { "Only the owner's outgoing trust is imported" }
        require(trust.subject != trust.target)
        validate(trust.state, trust.validity)
        db.transaction {
            require(SqlDelightExitScopeRepository(db).hasMembership(target)) { "Active Core membership required" }
            inventory.addRelation(CoreExitRelation(id, target, "TRUST", trust.context.domain))
            db.relationInventoryQueries.insertRelationTerms(id,
                AuthorityBasisJsonMapping.basis(AuthorityBasis(trustRef = listOf(trust))), "WITHDRAW_TRUST")
        }
    }

    override fun registerAgreement(id: String, target: ExitTarget, agreement: Agreement,
        exitPolicy: AgreementExitPolicy, obligations: List<ExitObligation>) {
        validate(agreement.state, agreement.validity)
        require(agreement.parties.size >= 2 && agreement.parties.distinct().size == agreement.parties.size)
        require(SubjectRef.Person(target.person) in agreement.parties)
        require(agreement.purpose.isNotBlank() && agreement.exitTerms.isNotBlank())
        require(agreement.terms.isNotEmpty() && agreement.terms.all(String::isNotBlank))
        require(obligations.all { it.target == target }) { "Obligations must belong to this participation" }
        db.transaction {
            require(SqlDelightExitScopeRepository(db).hasMembership(target)) { "Active Core membership required" }
            inventory.addRelation(CoreExitRelation(id, target, "AGREEMENT", agreement.purpose))
            db.relationInventoryQueries.insertRelationTerms(id, AgreementJsonMapping.encode(agreement), exitPolicy.name)
            obligations.forEach { obligation ->
                inventory.addObligation(obligation)
                db.relationInventoryQueries.insertRelationObligation(id, obligation.id)
            }
        }
    }

    override fun trusts(target: ExitTarget): List<StoredTrust> = db.relationInventoryQueries
        .selectRelationTerms(target.person.value, target.core.value).executeAsList().filter { it.kind == "TRUST" }
        .map { StoredTrust(it.relation_id, target, AuthorityBasisJsonMapping.basis(it.terms_json).trustRef.single(),
            ParticipationState.valueOf(it.state)) }

    override fun agreements(target: ExitTarget): List<StoredAgreement> = db.relationInventoryQueries
        .selectRelationTerms(target.person.value, target.core.value).executeAsList().filter { it.kind == "AGREEMENT" }
        .map { row ->
            val obligations = db.relationInventoryQueries.selectRelationObligations(row.relation_id)
                .executeAsList().map { ExitObligation(it.obligation_id, target, it.description,
                    ObligationState.valueOf(it.state), it.resolution) }
            StoredAgreement(row.relation_id, target, AgreementJsonMapping.decode(row.terms_json),
                AgreementExitPolicy.valueOf(row.exit_policy), obligations, ParticipationState.valueOf(row.state))
        }

    private fun validate(state: LifecycleState, validity: Validity) {
        require(state == LifecycleState.ACTIVE) { "Import only established active relations" }
        require(validity.until?.isAfter(validity.from) != false)
    }
}
