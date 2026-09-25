package sotaos.persistence

import sotaos.application.ports.AuthenticationRepository
import sotaos.application.ports.LocalCredentialRecord
import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SotaId
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightAuthenticationRepository(private val db: SotaOsDatabase) : AuthenticationRepository {
    override fun findPerson(unit: SotaId, providerId: String, providerSubject: String): PersonId? =
        db.schemaQueries.selectAuthenticationPerson(unit.value, providerId, providerSubject)
            .executeAsOneOrNull()?.let(::PersonId)

    override fun bind(unit: SotaId, providerId: String, providerSubject: String, person: PersonId, linkedAt: Instant) {
        db.schemaQueries.insertAuthenticationBinding(unit.value, providerId, providerSubject, person.value,
            linkedAt.toString())
    }

    override fun findLocalCredential(unit: SotaId, handle: String): LocalCredentialRecord? =
        db.schemaQueries.selectLocalCredential(unit.value, normalizeHandle(handle)).executeAsOneOrNull()?.let { row ->
            LocalCredentialRecord(row.salt_base64, row.hash_base64, row.iterations.toInt(),
                row.failed_attempts.toInt(), row.locked_until?.let(Instant::parse))
        }

    override fun enrollLocalCredential(
        unit: SotaId,
        handle: String,
        person: PersonId,
        saltBase64: String,
        hashBase64: String,
        iterations: Int,
        createdAt: Instant
    ) {
        val normalized = normalizeHandle(handle)
        db.transaction {
            db.schemaQueries.insertAuthenticationBinding(
                unit.value, LOCAL_PROVIDER_ID, normalized, person.value, createdAt.toString()
            )
            db.schemaQueries.insertLocalCredential(
                unit.value, normalized, saltBase64, hashBase64, iterations.toLong(), createdAt.toString()
            )
        }
    }

    override fun recordLocalFailure(unit: SotaId, handle: String, lockedUntil: Instant) {
        db.schemaQueries.recordLocalFailure(MAX_FAILED_ATTEMPTS, lockedUntil.toString(), unit.value,
            normalizeHandle(handle))
    }

    override fun clearLocalFailures(unit: SotaId, handle: String) {
        db.schemaQueries.clearLocalFailures(unit.value, normalizeHandle(handle))
    }

    companion object {
        private const val MAX_FAILED_ATTEMPTS = 5L
        const val LOCAL_PROVIDER_ID = LocalPassphraseAuthenticationProvider.PROVIDER_ID
        fun normalizeHandle(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
    }
}
