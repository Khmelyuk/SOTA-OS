package sotaos.test.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.persistence.SqlDelightRepositories
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator

class SyncAuthorityBoundaryTest : FunSpec({
    test("a contested local authority cannot execute a previously authorized decision") {
        withNodes { a, b ->
            val repositories = SqlDelightRepositories(a.store.database)
            val clock = Clock { syncTime }
            var nextId = 0
            val ids = IdGenerator { "local-${nextId++}" }
            val rights = RightsConstraintDecorator(listOf(NoAgentActionRule))
            val authorities = AuthorityService(repositories.authorities, clock, ids, rights)
            val actor = SubjectRef.Person(PersonId("actor"))
            val issuer = SubjectRef.Core(CoreId("core"))
            val invocation = ProtocolInvocation(actor, "execute fixture", syncContext)
            val authority = authorities.grant(
                ProtocolInvocation(issuer, "grant fixture", syncContext), issuer, actor,
                Scope(setOf("act")), syncContext, AuthorityBasis(note = "fixture"),
                Validity(syncTime, null), issuer
            )
            val service = MissionActionService(
                repositories.missions, repositories.decisions, repositories.actions, repositories.results,
                repositories.authorities, repositories.events, authorities::isValid, rights, clock, ids
            )
            val mission = service.formMission(ProtocolInvocation(issuer, "mission fixture", syncContext),
                issuer, "test contested authority", sotaos.domain.shared.Mode.CREATION)
            val decision = service.decide(invocation, actor, mission.id, authority.id, "act", emptyList())
            val entity = sotaos.domain.sync.SyncEntity(sotaos.domain.sync.SyncEntityKind.AUTHORITY, authority.id.value)
            a.service.recordLocal(record("grant-assertion", a.id, claim("one", entity)))
            b.service.recordLocal(record("other-assertion", b.id, claim("two", entity)))
            a.service.synchronize(b.id, transportTo(b))
            repositories.authorities.findById(authority.id)?.state shouldBe LifecycleState.CONTESTED
            repositories.authorities.findActiveFor(actor, syncContext) shouldBe emptyList()
            shouldThrow<ActionNotAuthorizedException> { service.execute(invocation, decision) }
            repositories.events.findByActor(actor) shouldBe emptyList()
            a.store.database.schemaQueries.selectAuthorityById(authority.id.value)
                .executeAsOne().state shouldBe LifecycleState.ACTIVE.name
        }
    }
})
