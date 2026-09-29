package sotaos.application.ports

import sotaos.domain.shared.*
import java.time.Instant

data class AuthorityRevocation(
    val authority: AuthorityId,
    val cascadeRoot: AuthorityId,
    val actor: SubjectRef,
    val reason: String,
    val occurredAt: Instant
)
