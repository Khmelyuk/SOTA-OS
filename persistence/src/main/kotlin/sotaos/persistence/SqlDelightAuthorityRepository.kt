package sotaos.persistence

import sotaos.application.ports.*
import sotaos.application.services.terminalAuthorityStates
import sotaos.domain.relation.Authority as DomainAuthority
import sotaos.domain.relation.coversDelegation
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightAuthorityRepository(private val db: SotaOsDatabase) : AuthorityRepository {
    override fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    override fun findChildren(parent: AuthorityId): List<DomainAuthority> = db.authorityLineageQueries
        .selectAuthorityChildren(parent.value).executeAsList().map { effectiveState(it.toDomain()) }

    override fun appendRevocation(record: AuthorityRevocation) {
        val (kind, id) = ValueJsonMapping.subject(record.actor)
        db.authorityLineageQueries.insertAuthorityRevocation(record.authority.value, record.cascadeRoot.value,
            kind, id, record.reason, record.occurredAt.toString())
    }

    override fun save(authority: DomainAuthority): DomainAuthority = transaction {
        val existing = findById(authority.id)
        require(existing == null || existing.parentAuthorityId == authority.parentAuthorityId) {
            "Authority parent is immutable."
        }
        if (existing == null || authority.state == LifecycleState.ACTIVE) validateParents(authority)
        val (issuerKind, issuerId) = ValueJsonMapping.subject(authority.issuer)
        val (subjectKind, subjectId) = ValueJsonMapping.subject(authority.subject)
        val (accountableKind, accountableId) = ValueJsonMapping.subject(authority.accountabilityTarget)
        if (db.schemaQueries.selectAuthorityById(authority.id.value).executeAsOneOrNull() == null) {
            db.schemaQueries.insertAuthority(authority.id.value, issuerKind, issuerId, subjectKind, subjectId,
                JsonMapping.scope(authority.scope), JsonMapping.context(authority.context),
                AuthorityBasisJsonMapping.basis(authority.basis), authority.validity.from.toString(),
                authority.validity.until?.toString(), accountableKind, accountableId, authority.state.name,
                authority.parentAuthorityId?.value)
        } else {
            db.schemaQueries.updateAuthority(issuerKind, issuerId, subjectKind, subjectId,
                JsonMapping.scope(authority.scope), JsonMapping.context(authority.context),
                AuthorityBasisJsonMapping.basis(authority.basis), authority.validity.from.toString(),
                authority.validity.until?.toString(), accountableKind, accountableId,
                authority.state.name, authority.id.value)
        }
        authority
    }

    override fun findById(id: AuthorityId): DomainAuthority? = db.schemaQueries.selectAuthorityById(id.value)
        .executeAsOneOrNull()?.toDomain()?.let(::effectiveState)

    override fun findActiveFor(subject: SubjectRef, context: Context): List<DomainAuthority> {
        val (kind, id) = ValueJsonMapping.subject(subject)
        return db.schemaQueries.selectAuthoritiesBySubject(kind, id).executeAsList()
            .map { effectiveState(it.toDomain()) }
            .filter { it.state == LifecycleState.ACTIVE && it.context == context }
    }

    private fun validateParents(authority: DomainAuthority) {
        val visited = mutableSetOf(authority.id)
        var child = authority
        while (child.parentAuthorityId != null) {
            val parentId = requireNotNull(child.parentAuthorityId)
            require(visited.add(parentId)) { "Cyclic delegation." }
            val parent = requireNotNull(findById(parentId)) { "Missing delegation parent." }
            require(parent.coversDelegation(child)) { "Delegation exceeds parent terms." }
            require(parent.state == LifecycleState.ACTIVE) { "Delegation parent is not active." }
            child = parent
        }
    }

    private fun effectiveState(authority: DomainAuthority): DomainAuthority =
        if (authority.state !in terminalAuthorityStates &&
            db.syncQueries.hasConflict("AUTHORITY", authority.id.value).executeAsOne() > 0) {
            authority.copy(state = LifecycleState.CONTESTED)
        } else {
            authority
        }
}

private fun sotaos.persistence.Authority.toDomain(): DomainAuthority = DomainAuthority(
    id = AuthorityId(authority_id), issuer = ValueJsonMapping.subject(issuer_kind, issuer_id),
    subject = ValueJsonMapping.subject(subject_kind, subject_id), scope = JsonMapping.scope(scope_json),
    context = JsonMapping.context(context_json), basis = AuthorityBasisJsonMapping.basis(basis_json),
    validity = Validity(Instant.parse(valid_from), valid_until?.let(Instant::parse)),
    accountabilityTarget = ValueJsonMapping.subject(accountable_kind, accountable_id),
    state = LifecycleState.valueOf(state), parentAuthorityId = parent_authority_id?.let(::AuthorityId)
)
