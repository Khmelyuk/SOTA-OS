package sotaos.persistence

import sotaos.application.ports.*
import sotaos.persistence.db.SotaOsDatabase

class SqlDelightLocalCredentialLifecycleRepository(
    private val db: SotaOsDatabase
) : LocalCredentialLifecycleRepository {
    private val authentication = SqlDelightAuthenticationRepository(db)

    override fun rotate(change: LocalCredentialChange, material: LocalCredentialMaterial) = db.transaction {
        checkCurrent(change)
        db.localCredentialLifecycleQueries.rotateLocalCredential(material.saltBase64, material.hashBase64,
            material.iterations.toLong(), change.occurredAt.toString(), change.unit.value, change.handle,
            change.expectedRevision)
        audit(change, "ROTATE")
    }

    override fun revoke(change: LocalCredentialChange) = db.transaction {
        checkCurrent(change)
        db.localCredentialLifecycleQueries.revokeLocalCredential(change.occurredAt.toString(),
            change.unit.value, change.handle, change.expectedRevision)
        audit(change, "REVOKE")
    }

    private fun checkCurrent(change: LocalCredentialChange) {
        require(change.handle == SqlDelightAuthenticationRepository.normalizeHandle(change.handle))
        val current = requireNotNull(authentication.findLocalCredential(change.unit, change.handle))
        require(current.revokedAt == null && current.revision == change.expectedRevision) {
            "Credential changed since authentication; authenticate again."
        }
        require(authentication.findPerson(change.unit, LocalPassphraseAuthenticationProvider.PROVIDER_ID,
            change.handle) == change.person) { "Credential does not belong to authenticated Person." }
    }

    private fun audit(change: LocalCredentialChange, operation: String) {
        val revision = Math.addExact(change.expectedRevision, 1)
        check(authentication.findLocalCredential(change.unit, change.handle)?.revision == revision)
        db.localCredentialLifecycleQueries.insertCredentialAudit(change.unit.value, change.handle,
            revision, operation, change.person.value, change.occurredAt.toString())
    }
}
