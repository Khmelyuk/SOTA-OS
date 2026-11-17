package sotaos.test.architecture_invariants

import sotaos.application.ports.*
import sotaos.application.services.AuthorityService
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.test.fakes.*

internal class AuthorityFixture {
    val clock = FixedClock()
    val issuer = SubjectRef.Core(CoreId("issuer"))
    val holder = SubjectRef.Person(PersonId("holder"))
    val recipient = SubjectRef.Person(PersonId("recipient"))
    val context = Context("delegation")
    val scope = Scope(setOf("read", "write"), setOf("resource:one"))
    val source = Authority(AuthorityId("source"), issuer, holder, scope, context, AuthorityBasis(),
        Validity(clock.now().minusSeconds(60), clock.now().plusSeconds(60)), issuer, LifecycleState.ACTIVE)
    val checks = mutableListOf<RightsConstraintRequest>()
    var denyRights = false
    var writes = 0
    private val backing = InMemoryAuthorityRepository()
    val repository = object : AuthorityRepository by backing {
        override fun save(authority: Authority): Authority {
            writes++
            return backing.save(authority)
        }
    }
    val rights = RightsConstraint { request ->
        checks.add(request)
        check(!denyRights) { "denied by fixture policy" }
        RightsConstraintDecision(setOf("fixture"))
    }
    val service = AuthorityService(repository, clock, SequentialIdGenerator(), rights)

    fun delegate(
        validity: Validity = Validity(clock.now(), source.validity.until),
        requestedScope: Scope = Scope(setOf("read"), setOf("resource:one")),
        requestedContext: Context = context,
        accountableTo: SubjectRef = issuer
    ): Authority = service.grant(testInvocation(holder), holder, recipient, requestedScope,
        requestedContext, AuthorityBasis(), validity, accountableTo)
}
