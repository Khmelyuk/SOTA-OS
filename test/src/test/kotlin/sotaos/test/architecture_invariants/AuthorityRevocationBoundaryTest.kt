package sotaos.test.architecture_invariants

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.*
import sotaos.test.fakes.testInvocation

class AuthorityRevocationBoundaryTest : FunSpec({
    test("a caller cannot revoke another issuer's grant by replacing the issuer in its input copy") {
        val f = AuthorityFixture()
        f.repository.save(f.source)
        shouldThrow<IllegalArgumentException> {
            f.service.revoke(testInvocation(f.recipient), f.source.copy(issuer = f.recipient), "forged")
        }
        f.repository.findById(f.source.id) shouldBe f.source
        f.writes shouldBe 1
        f.checks shouldBe emptyList()
    }

    test("revoking an unknown identifier cannot insert a caller supplied authority") {
        val f = AuthorityFixture()
        shouldThrow<IllegalArgumentException> { f.service.revoke(testInvocation(f.issuer), f.source, "unknown") }
        f.repository.findById(f.source.id) shouldBe null
        f.writes shouldBe 0
        f.checks shouldBe emptyList()
    }

    test("revocation preserves stored terms and evaluates rights against the stored authority") {
        val f = AuthorityFixture()
        val stored = f.source.copy(subject = f.recipient, scope = Scope(setOf("limited")))
        f.repository.save(stored)
        val revoked = f.service.revoke(testInvocation(f.issuer), f.source, "stale input")
        revoked shouldBe stored.copy(state = LifecycleState.REVOKED)
        f.checks.single().authority shouldBe stored
        f.checks.single().target shouldBe stored.subject
        f.repository.findById(stored.id) shouldBe revoked
    }

    test("an already revoked authority is idempotent but still requires issuer and rights checks") {
        val f = AuthorityFixture()
        val revoked = f.source.copy(state = LifecycleState.REVOKED)
        f.repository.save(revoked)
        f.service.revoke(testInvocation(f.issuer), f.source, "retry") shouldBe revoked
        f.writes shouldBe 1
        f.checks.size shouldBe 1
        f.denyRights = true
        shouldThrow<IllegalStateException> { f.service.revoke(testInvocation(f.issuer), f.source, "retry") }
        f.repository.findById(f.source.id) shouldBe revoked
    }
})
