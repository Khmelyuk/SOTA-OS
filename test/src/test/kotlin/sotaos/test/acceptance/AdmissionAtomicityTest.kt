package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.LocalCredentialChange
import sotaos.application.services.AuthenticationDeniedException
import sotaos.domain.shared.LifecycleState

class AdmissionAtomicityTest : FunSpec({
    test("invitation entity and human audit failures roll back together and fresh proof can retry after reopen") {
        for (table in listOf("membership", "event")) {
            AdmissionFixture().use { f ->
                f.credentials.driver.execute(null, "CREATE TRIGGER admission_failure BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected admission failure'); END", 0)
                val runtime = f.runtime()
                val challenge = runtime.begin(f.credentials.handle)
                val proof = f.credentials.oldPassword.toCharArray()
                shouldThrow<Exception> { runtime.invite(challenge, proof, f.request) }
                proof.all { it == '\u0000' } shouldBe true
                f.count("membership") shouldBe 0L
                f.count("event") shouldBe 0L
                shouldThrow<AuthenticationDeniedException> {
                    runtime.invite(challenge, f.credentials.oldPassword.toCharArray(), f.request)
                }
                f.credentials.driver.execute(null, "DROP TRIGGER admission_failure", 0)
                f.credentials.reopen()
                f.issue().membership.state shouldBe LifecycleState.PROPOSED
                f.count("event") shouldBe 1L
            }
        }
    }

    test("acceptance mutation and audit failures preserve the proposal and permit a fresh retry") {
        for (table in listOf("membership", "event")) {
            AdmissionFixture().use { f ->
                val invitation = f.issue()
                val operation = if (table == "membership") "UPDATE" else "INSERT"
                f.credentials.driver.execute(null, "CREATE TRIGGER admission_failure BEFORE $operation ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected acceptance failure'); END", 0)
                shouldThrow<Exception> { f.accept(f.runtime(), invitation) }
                f.count("event") shouldBe 1L
                f.repos.memberships.findRecord(invitation.membership) shouldBe invitation.membership
                f.credentials.driver.execute(null, "DROP TRIGGER admission_failure", 0)
                f.credentials.reopen()
                f.accept(f.runtime(), invitation).state shouldBe LifecycleState.ACTIVE
                f.count("event") shouldBe 2L
            }
        }
    }

    test("credential revocation after challenge issuance denies invitation and clears proof") {
        AdmissionFixture().use { f ->
            val runtime = f.runtime()
            val challenge = runtime.begin(f.credentials.handle)
            f.credentials.mutations.revoke(LocalCredentialChange(f.credentials.unit, f.credentials.handle,
                f.credentials.person, f.credentials.credential().revision, f.credentials.now))
            val proof = f.credentials.oldPassword.toCharArray()
            shouldThrow<AuthenticationDeniedException> { runtime.invite(challenge, proof, f.request) }
            proof.all { it == '\u0000' } shouldBe true
            f.count("membership") shouldBe 0L
            f.count("event") shouldBe 0L
        }
    }
})
