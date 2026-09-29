package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.services.*
import sotaos.domain.shared.*
import sotaos.test.fakes.InMemoryAuthorityRepository

class DelegationBoundsTest : FunSpec({
    test("redelegation cannot widen actions resources time context or accountable Core") {
        ExitFixture().use { f ->
            val root = f.repos.authorities.save(f.authority(f.core).copy(id = AuthorityId("bounded"),
                scope = Scope(setOf("execute"), setOf("resource-a")),
                validity = Validity(f.now.minusSeconds(1), f.now.plusSeconds(60))))
            f.delegate(root, delegateB).parentAuthorityId shouldBe root.id
            val invalid = listOf<() -> Unit>(
                { f.delegate(root, delegateC, scope = Scope(setOf("admin"), setOf("resource-a"))) },
                { f.delegate(root, delegateC, scope = Scope(setOf("execute"), setOf("resource-b"))) },
                { f.delegate(root, delegateC, scope = Scope(setOf("execute"))) },
                { f.delegate(root, delegateC, validity = Validity(f.now.minusSeconds(2), f.now.plusSeconds(30))) },
                { f.delegate(root, delegateC, validity = Validity(f.now, f.now.plusSeconds(61))) },
                { f.delegate(root, delegateC, validity = Validity(f.now, null)) },
                { f.delegate(root, delegateC, context = Context("other-context")) },
                { f.delegate(root, delegateC, accountable = SubjectRef.Core(f.other)) }
            )
            invalid.forEach { shouldThrow<AuthorityException> { it() } }
            f.repos.authorities.findChildren(root.id).size shouldBe 1
        }
    }
    test("issuer must select among multiple parents and explicit parent must belong to issuer") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val alternate = f.repos.authorities.save(root.copy(id = AuthorityId("alternate")))
            shouldThrow<AuthorityException> { f.delegate(root, delegateB, parentId = null) }
            f.delegate(root, delegateB).parentAuthorityId shouldBe root.id
            f.delegate(alternate, delegateC).parentAuthorityId shouldBe alternate.id
            val unrelated = f.repos.authorities.save(root.copy(id = AuthorityId("unrelated"), subject = delegateD))
            shouldThrow<AuthorityException> { f.delegate(root, delegateB, parentId = unrelated.id) }
        }
    }
    test("expired future suspended and contested ancestors deny further delegation") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            f.repos.authorities.save(root.copy(state = LifecycleState.SUSPENDED))
            f.authorityService().isValid(child, child.scope, f.now) shouldBe false
            shouldThrow<AuthorityException> { f.delegate(child, delegateC) }
            listOf(Validity(f.now.minusSeconds(60), f.now.minusSeconds(1)),
                Validity(f.now.plusSeconds(60), null)).forEachIndexed { index, validity ->
                val timed = f.repos.authorities.save(root.copy(id = AuthorityId("timed-$index"), validity = validity))
                shouldThrow<AuthorityException> { f.delegate(timed, delegateC) }
            }
            f.store.database.syncQueries.saveConflict("AUTHORITY", child.id.value, "event-a", "event-b")
            f.authorityService().isValid(requireNotNull(f.repos.authorities.findById(child.id)), child.scope, f.now)
                .shouldBe(false)
        }
    }
    test("SQLite rejects parent rewriting cycles and cross-Core stored links") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            val child = f.delegate(root, delegateB)
            val grandchild = f.delegate(child, delegateC)
            shouldThrow<IllegalArgumentException> {
                f.repos.authorities.save(root.copy(parentAuthorityId = grandchild.id))
            }
            shouldThrow<IllegalArgumentException> {
                f.repos.authorities.save(child.copy(id = AuthorityId("cross-core"),
                    accountabilityTarget = SubjectRef.Core(f.other)))
            }
            shouldThrow<Exception> {
                f.driver.execute(null, "UPDATE authority SET parent_authority_id = 'missing' " +
                    "WHERE authority_id = 'authority-chosen'", 0)
            }
            shouldThrow<Exception> { f.repos.authorities.save(root.copy(scope = Scope(setOf("admin")))) }
            f.repos.authorities.findById(root.id)?.scope shouldBe root.scope
        }
    }
    test("cycles and missing ancestors fail closed even with a corrupt repository") {
        ExitFixture().use { f ->
            val repository = InMemoryAuthorityRepository()
            val a = f.authority(f.core).copy(id = AuthorityId("a"), issuer = delegateC, subject = delegateB,
                parentAuthorityId = AuthorityId("b"))
            val b = a.copy(id = AuthorityId("b"), issuer = delegateB, subject = delegateC,
                parentAuthorityId = AuthorityId("a"))
            repository.save(a)
            AuthorityLineage(repository).isValid(a, a.scope, f.now) shouldBe false
            repository.save(b)
            AuthorityLineage(repository).isValid(a, a.scope, f.now) shouldBe false
        }
    }
    test("self delegation is denied and stale authority snapshots cannot authorize work") {
        ExitFixture().use { f ->
            val root = f.authority(f.core)
            shouldThrow<IllegalArgumentException> { f.delegate(root, root.subject) }
            val child = f.delegate(root, delegateB)
            f.authorityService().revoke(f.invocation, child, "withdraw")
            f.authorityService().isValid(child, child.scope, f.now) shouldBe false
        }
    }
})
