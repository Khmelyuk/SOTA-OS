package sotaos.test.rehearsal

import sotaos.api.agency.SignedP05Runtime
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.agency.Decision
import sotaos.domain.agency.Outcome
import sotaos.domain.collective.Core
import sotaos.domain.collective.Membership
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightExitInventoryRepository
import sotaos.persistence.SqlDelightExitScopeRepository
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.protocol.JsonSyncRecordCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal fun bootstrapCore(a: RehearsalNode, b: RehearsalNode): Core {
    val core = a.localAccess { _, repositories ->
        val authorities = AuthorityService(repositories.authorities, rehearsalClock, rehearsalIds, rehearsalRights)
        CollectiveService(repositories.cores, repositories.memberships, repositories.authorities,
            authorities::isValid, rehearsalClock, rehearsalIds, rehearsalRights)
            .createCore(a.invocation(repositories, work), "Rehearsal Core", a.person.id)
    }
    listOf(a, b).forEach { node -> node.localAccess { store, repositories ->
        if (repositories.cores.findById(core.id) == null) repositories.cores.save(core)
        listOf(a, b).forEach { member ->
            if (repositories.persons.findById(member.person.id) == null) repositories.persons.save(member.person)
            if (repositories.memberships.findActiveFor(member.person.id, SubjectRef.Core(core.id)) == null) {
                repositories.memberships.save(Membership(member.person.id, SubjectRef.Core(core.id), null,
                    rehearsalClock.now(), null, LifecycleState.ACTIVE))
            }
        }
        // Deliberate shared fixture state, not a projection constructed from received remote events.
        val target = ExitTarget(a.person.id, core.id)
        val inventory = SqlDelightExitInventoryRepository(store.database)
        inventory.addRelation(CoreExitRelation("fixture-relation", target, "TRUST", "Scoped rehearsal relation"))
        inventory.addObligation(ExitObligation("fixture-obligation", target,
            "Retain responsibility", ObligationState.OPEN))
    } }
    return core
}

internal fun executeCoreLoop(node: RehearsalNode, core: Core): Decision = node.localAccess { store, repositories ->
    val invocation = node.invocation(repositories, work)
    val authority = AuthorityService(repositories.authorities, rehearsalClock, rehearsalIds, rehearsalRights).grant(
        ProtocolInvocation(SubjectRef.Core(core.id), "Rehearsal Core authority bootstrap", work),
        SubjectRef.Core(core.id), node.actor, Scope(setOf("rehearsal.execute")), work,
        AuthorityBasis(note = "Explicit fixture Core grants one action"), Validity(rehearsalClock.now(), null),
        SubjectRef.Core(core.id))
    val runtime = SignedP05Runtime(store, node.local, governance, rehearsalClock, rehearsalIds, { actor ->
        check(actor == node.actor)
        node.signer
    })
    val mission = runtime.formMission(invocation, node.actor, "Complete the offline Core Loop", Mode.CREATION)
    val decision = runtime.decide(invocation, node.actor, mission.id, authority.id, "rehearsal.execute", emptyList())
    val action = runtime.execute(invocation, decision)
    runtime.recordResult(invocation, action, Outcome.SUCCESS, "Offline action succeeded")
    val event = repositories.events.findByActor(node.actor).single()
    val memory = MemoryService(repositories.experiences, repositories.knowledge,
        rehearsalClock, rehearsalIds, rehearsalRights)
    val experience = memory.createExperience(invocation, listOf(event), "Offline result", "Rehearsal only")
    val knowledge = memory.proposeKnowledgeCandidate(invocation, experience, "Signed action recorded offline")
    check(repositories.knowledge.findById(knowledge.id) == knowledge)
    val record = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec()).records().single()
    runtime.sync.integrity.check(record)
    check(node.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)))
    decision
}

internal fun leaveCore(node: RehearsalNode, core: Core, decision: Decision, archive: Path): ExitReceipt =
    node.localAccess { store, repositories ->
        val runtime = SignedP10Runtime(store, node.local, governance, rehearsalClock, rehearsalIds, { actor ->
            check(actor == node.actor)
            node.signer
        })
        val receipt = runtime.exit.leave(node.invocation(repositories, work), core.id, archive)
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
            .joinToString("") { "%02x".format(it) }
        check(hash == receipt.exportSha256)
        check(Files.getPosixFilePermissions(archive) == PosixFilePermissions.fromString("rw-------"))
        val scope = SqlDelightExitScopeRepository(store.database)
        check(!scope.hasMembership(receipt.target) && !scope.hasDelegations(receipt.target))
        check(!scope.hasRelations(receipt.target) && !scope.hasUnresolvedObligations(receipt.target))
        check(SqlDelightExitInventoryRepository(store.database).obligations(receipt.target).single().state ==
            ObligationState.RETAINED)
        val actions = SignedP05Runtime(store, node.local, governance, rehearsalClock, rehearsalIds, { node.signer })
        val denied = runCatching { actions.execute(node.invocation(repositories, work), decision) }.exceptionOrNull()
        check(denied is ActionNotAuthorizedException) { "Exit must revoke the action authority." }
        receipt
    }
