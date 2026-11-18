package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.LifecycleState

class MembershipPersistenceTest : FunSpec({
    test("legacy membership storage IDs are preserved when a recorded invitation is accepted and left") {
        SignedActionFixture().use { f ->
            val invitation = f.invitation()
            f.driver.execute(null, "UPDATE membership SET membership_id = 'legacy-id'", 0)
            f.reopen()
            f.repos.memberships.findRecord(invitation) shouldBe invitation
            val active = f.collective().accept(f.invocation, invitation)
            f.count("membership") shouldBe 1L
            f.store.database.schemaQueries.selectMembershipById("legacy-id").executeAsOne().state shouldBe "ACTIVE"
            f.collective().leave(f.invocation, active)
            f.reopen()
            f.repos.memberships.findRecord(invitation)?.state shouldBe LifecycleState.REVOKED
            f.store.database.schemaQueries.selectMembershipById("legacy-id").executeAsOne().state shouldBe "REVOKED"
        }
    }

    test("ambiguous legacy membership terms are rejected without choosing or modifying a row") {
        SignedActionFixture().use { f ->
            val invitation = f.invitation()
            f.driver.execute(null, """INSERT INTO membership
                SELECT 'duplicate', person_id, collective_kind, collective_id, role_id, start_at, end_at, state
                FROM membership""", 0)
            shouldThrow<IllegalStateException> { f.collective().accept(f.invocation, invitation) }
            f.count("membership") shouldBe 2L
            f.repos.memberships.findActiveFor(f.actor.id, membershipCore) shouldBe null
        }
    }

    test("failed membership transitions preserve the persisted state across reopen and allow retry") {
        for (operation in listOf("accept", "leave", "remove")) {
            SignedActionFixture().use { f ->
                val invitation = f.invitation()
                val before = if (operation == "accept") invitation else f.collective().accept(f.invocation, invitation)
                val authority = f.removalAuthority()
                f.driver.execute(null, "CREATE TRIGGER membership_failure BEFORE UPDATE ON membership " +
                    "BEGIN SELECT RAISE(ABORT, 'injected transition failure'); END", 0)
                shouldThrow<Exception> {
                    when (operation) {
                        "accept" -> f.collective().accept(f.invocation, before)
                        "leave" -> f.collective().leave(f.invocation, before)
                        else -> f.collective().remove(f.invocation, before, authority.id)
                    }
                }
                f.driver.execute(null, "DROP TRIGGER membership_failure", 0)
                f.reopen()
                f.repos.memberships.findRecord(before) shouldBe before
                val after = when (operation) {
                    "accept" -> f.collective().accept(f.invocation, before)
                    "leave" -> f.collective().leave(f.invocation, before)
                    else -> f.collective().remove(f.invocation, before, authority.id)
                }
                f.repos.memberships.findRecord(before) shouldBe after
                f.count("membership") shouldBe 1L
            }
        }
    }
})
