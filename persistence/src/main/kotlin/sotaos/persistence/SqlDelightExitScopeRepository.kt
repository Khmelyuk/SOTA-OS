package sotaos.persistence

import sotaos.application.ports.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightExitScopeRepository(private val db: SotaOsDatabase) : ExitScopeRepository {
    override fun hasMembership(target: ExitTarget): Boolean = db.schemaQueries
        .selectActiveMembership(target.person.value, "CORE", target.core.value).executeAsOneOrNull() != null
    override fun revokeDelegations(target: ExitTarget) {
        db.exitQueries.revokeExitDelegations(target.core.value, target.person.value)
    }
    override fun hasDelegations(target: ExitTarget): Boolean = db.exitQueries
        .countExitDelegations(target.core.value, target.person.value).executeAsOne() > 0
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
