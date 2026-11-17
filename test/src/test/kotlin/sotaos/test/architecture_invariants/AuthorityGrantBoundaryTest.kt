package sotaos.test.architecture_invariants

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.services.AuthorityException
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.test.fakes.testInvocation

class AuthorityGrantBoundaryTest : FunSpec({
    for (kind in listOf("expired", "future")) {
        test("an ACTIVE but $kind authority cannot authorize a new delegation") {
            val f = AuthorityFixture()
            val now = f.clock.now()
            val validity = if (kind == "expired") Validity(now.minusSeconds(60), now)
                else Validity(now.plusSeconds(1), null)
            f.repository.save(f.source.copy(validity = validity))
            shouldThrow<AuthorityException> { f.delegate() }
            f.writes shouldBe 1
            f.checks shouldBe emptyList()
        }
    }

    test("a delegated interval must fit wholly inside one currently valid authority") {
        val f = AuthorityFixture()
        f.repository.save(f.source)
        val now = f.clock.now()
        val invalid = listOf(Validity(now, null), Validity(now, now.plusSeconds(61)),
            Validity(now.minusSeconds(61), now.plusSeconds(1)))
        invalid.forEach { validity -> shouldThrow<AuthorityException> { f.delegate(validity) } }
        f.writes shouldBe 1
        f.checks shouldBe emptyList()
    }

    test("delegation cannot change accountability or combine partial grants into a covering grant") {
        val f = AuthorityFixture()
        f.repository.save(f.source)
        val elsewhere = SubjectRef.Core(CoreId("elsewhere"))
        shouldThrow<AuthorityException> { f.delegate(accountableTo = elsewhere) }
        f.repository.save(f.source.copy(id = AuthorityId("other"), scope = Scope(setOf("write")),
            accountabilityTarget = elsewhere))
        shouldThrow<AuthorityException> { f.delegate(accountableTo = elsewhere) }
        f.writes shouldBe 2
        f.checks shouldBe emptyList()
    }

    test("a narrower delegation succeeds at the inclusive start and equal exclusive end") {
        val f = AuthorityFixture()
        f.repository.save(f.source.copy(validity = Validity(f.clock.now(), f.source.validity.until)))
        val delegated = f.delegate()
        delegated.validity shouldBe Validity(f.clock.now(), f.source.validity.until)
        f.repository.findById(delegated.id) shouldBe delegated
        f.checks.single().protocol shouldBe "P04"
        f.checks.single().authority shouldBe f.repository.findById(f.source.id)
    }

    test("unbounded authority permits finite or unbounded delegation and still checks rights") {
        val f = AuthorityFixture()
        f.repository.save(f.source.copy(validity = Validity(f.clock.now(), null)))
        f.delegate().validity.until shouldBe f.source.validity.until
        f.delegate(Validity(f.clock.now().plusSeconds(10), null)).validity.until shouldBe null
        f.denyRights = true
        shouldThrow<IllegalStateException> { f.delegate() }
        f.writes shouldBe 3
    }

    test("root issuers cannot create empty, reversed or already expired validity intervals") {
        val f = AuthorityFixture()
        val now = f.clock.now()
        val invalid = listOf(Validity(now, now), Validity(now, now.minusSeconds(1)),
            Validity(now.minusSeconds(2), now.minusSeconds(1)))
        invalid.forEach { validity ->
            shouldThrow<IllegalArgumentException> {
                f.service.grant(testInvocation(f.issuer), f.issuer, f.holder, f.scope, f.context,
                    AuthorityBasis(), validity, f.issuer)
            }
        }
        f.writes shouldBe 0
        f.checks shouldBe emptyList()
    }
})
