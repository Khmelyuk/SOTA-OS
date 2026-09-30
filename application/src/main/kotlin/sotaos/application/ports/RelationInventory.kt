package sotaos.application.ports

import sotaos.domain.relation.Agreement
import sotaos.domain.relation.Trust

/** Explicitly supported local participation policy; free text is never executed as policy. */
enum class AgreementExitPolicy { WITHDRAW_PARTICIPATION_RETAIN_OBLIGATIONS }
enum class ParticipationState { ACTIVE, CLOSED }
data class StoredTrust(val id: String, val target: ExitTarget, val terms: Trust, val state: ParticipationState)
data class StoredAgreement(
    val id: String, val target: ExitTarget, val terms: Agreement, val exitPolicy: AgreementExitPolicy,
    val obligations: List<ExitObligation>, val state: ParticipationState
)

/**
 * Trusted infrastructure import of already established relations, not a consent or enrollment API.
 * IDs identify a person's Core-scoped participation. Original terms remain immutable after exit.
 */
interface RelationInventory {
    fun registerTrust(id: String, target: ExitTarget, trust: Trust)
    fun registerAgreement(id: String, target: ExitTarget, agreement: Agreement,
        exitPolicy: AgreementExitPolicy, obligations: List<ExitObligation>)
    fun trusts(target: ExitTarget): List<StoredTrust>
    fun agreements(target: ExitTarget): List<StoredAgreement>
}
