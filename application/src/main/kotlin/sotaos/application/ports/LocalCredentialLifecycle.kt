package sotaos.application.ports

import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SotaId
import java.time.Instant

/** Provider-specific verifier, never a plaintext secret. Do not log this value. */
data class LocalCredentialMaterial(val saltBase64: String, val hashBase64: String, val iterations: Int)

fun interface LocalCredentialEncoder {
    /** Implementations must erase the supplied secret on every exit. */
    fun encode(passphrase: CharArray): LocalCredentialMaterial
}

data class LocalCredentialChange(
    val unit: SotaId,
    val handle: String,
    val person: PersonId,
    val expectedRevision: Long,
    val occurredAt: Instant
)

/** Trusted adapter boundary. Mutations compare the authenticated revision and commit with their audit. */
interface LocalCredentialLifecycleRepository {
    fun rotate(change: LocalCredentialChange, material: LocalCredentialMaterial)
    fun revoke(change: LocalCredentialChange)
}
