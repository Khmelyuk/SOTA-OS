package sotaos.test.acceptance

import sotaos.api.memory.SignedP07Runtime
import sotaos.application.ports.*
import sotaos.domain.agency.Outcome
import sotaos.domain.memory.Event
import sotaos.domain.memory.Experience
import sotaos.domain.shared.*
import sotaos.security.*
import java.util.UUID

internal fun SignedActionFixture.memory(
    node: String = "source",
    signers: (SubjectRef) -> EventSigner = { signer },
    actors: Set<SubjectRef> = setOf(actor),
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule)),
    ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
) = SignedP07Runtime(store, LocalSyncIdentity(SotaId(node), actors), Context("governance"),
    Clock { now }, ids, signers, rights)

internal fun SignedActionFixture.sourceEvents(): List<Event> {
    val runtime = runtime()
    val action = runtime.execute(invocation, decision(runtime))
    runtime.recordResult(invocation, action, Outcome.SUCCESS, "Delivered")
    return journal.records().map { it.event }
}

internal fun SignedActionFixture.experience(runtime: SignedP07Runtime): Experience =
    runtime.createExperience(invocation, sourceEvents(), "Conclusion", "Conditions")
