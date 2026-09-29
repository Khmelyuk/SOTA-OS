package sotaos.persistence

import sotaos.application.ports.*
import sotaos.application.services.AuthorityCascade
import sotaos.application.services.terminalAuthorityStates
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightExitScopeRepository(private val db: SotaOsDatabase) : ExitScopeRepository {
    override fun hasMembership(target: ExitTarget): Boolean = db.schemaQueries
        .selectActiveMembership(target.person.value, "CORE", target.core.value).executeAsOneOrNull() != null
    private val authorities = SqlDelightAuthorityRepository(db)
    private val cascade = AuthorityCascade(authorities)
    private fun roots(target: ExitTarget) = db.authorityLineageQueries
        .selectExitAuthorityRoots(target.core.value, target.person.value).executeAsList()
        .map { requireNotNull(authorities.findById(AuthorityId(it.authority_id))) }

    override fun revokeDelegations(target: ExitTarget, at: Instant, reason: String) {
        cascade.revoke(roots(target), SubjectRef.Person(target.person), reason, at)
    }
    override fun hasDelegations(target: ExitTarget): Boolean = roots(target).any { root ->
        cascade.family(root).any { it.state !in terminalAuthorityStates }
    }
    override fun closeRelations(target: ExitTarget) {
        db.exitQueries.closeExitRelations(target.person.value, target.core.value)
    }
    override fun hasRelations(target: ExitTarget): Boolean = db.exitQueries
        .countExitRelations(target.person.value, target.core.value).executeAsOne() > 0
    override fun retainObligations(target: ExitTarget, declaration: String, at: Instant) {
        db.exitQueries.retainObligations(declaration, at.toString(), target.person.value, target.core.value)
    }
    override fun hasUnresolvedObligations(target: ExitTarget): Boolean = db.exitQueries
        .countOpenObligations(target.person.value, target.core.value).executeAsOne() > 0
    override fun terminateMembership(target: ExitTarget, at: Instant) {
        require(hasMembership(target)) { "Membership was changed outside this exit." }
        db.exitQueries.terminateMembership(at.toString(), target.person.value, target.core.value)
    }
}
