package sotaos.domain.shared

/** Authenticated caller and declared purpose for a state-changing protocol command. */
data class ProtocolInvocation(
    val actor: SubjectRef,
    val purpose: String,
    val context: Context? = null
) {
    init { require(purpose.isNotBlank()) { "Protocol invocation purpose must not be blank." } }
}
