package sotaos.application.ports

import sotaos.domain.shared.SubjectRef
import java.time.Instant

data class ActorSigningKeyRecord(
    val actor: SubjectRef,
    val keyId: String,
    val publicKeyBase64: String
) {
    init {
        require(keyId.isNotBlank()) { "Actor signing key ID must not be blank." }
        require(publicKeyBase64.isNotBlank()) { "Actor public key must not be blank." }
    }
}

interface ActorSigningKeyRepository {
    fun save(record: ActorSigningKeyRecord, occurredAt: Instant): ActorSigningKeyRecord
    fun findByActor(actor: SubjectRef): ActorSigningKeyRecord?
    fun findAll(): List<ActorSigningKeyRecord>
    fun revoke(actor: SubjectRef, keyId: String, occurredAt: Instant)
    fun auditByActor(actor: SubjectRef): List<ActorSigningKeyAuditRecord>
}

enum class ActorSigningKeyOperation { ENROLL, ROTATE, REVOKE }

data class ActorSigningKeyAuditRecord(
    val actor: SubjectRef,
    val keyId: String,
    val operation: ActorSigningKeyOperation,
    val publicKeyBase64: String?,
    val occurredAt: Instant
)
