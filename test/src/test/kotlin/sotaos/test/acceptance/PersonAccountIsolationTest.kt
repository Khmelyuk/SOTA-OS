package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.shared.*

class PersonAccountIsolationTest : FunSpec({
    test("rotate and revoke affect real authentication and preserve Person Identity and binding after reopen") {
        CredentialFixture().use { f ->
            val person = f.repositories.persons.findById(f.person)
            val identities = f.repositories.identities.findByPerson(f.person)
            val runtime = f.runtime
            val old = f.oldPassword.toCharArray()
            val replacement = f.newPassword.toCharArray()
            runtime.rotate(runtime.begin(" OWNER "), old, replacement)
            old.all { it == '\u0000' } shouldBe true
            replacement.all { it == '\u0000' } shouldBe true
            f.reopen()
            shouldThrow<AuthenticationDeniedException> { f.login() }
            f.login(f.newPassword).person shouldBe f.person
            val resumed = f.runtime
            resumed.revoke(resumed.begin(f.handle), f.newPassword.toCharArray())
            f.reopen()
            shouldThrow<AuthenticationDeniedException> { f.login(f.newPassword) }
            shouldThrow<IllegalArgumentException> {
                f.provider.enroll(f.unit, f.handle, f.person, f.newPassword.toCharArray(), f.now)
            }
            f.credential().revision shouldBe 3L
            f.credential().revokedAt shouldBe f.now
            f.repositories.persons.findById(f.person) shouldBe person
            f.repositories.identities.findByPerson(f.person) shouldBe identities
            f.authentication.findPerson(f.unit, f.provider.providerId, f.handle) shouldBe f.person
            f.history().map { it.operation } shouldBe listOf("ENROLL", "ROTATE", "REVOKE")
            f.history().drop(1).map { it.actor_person_id } shouldBe listOf(f.person.value, f.person.value)
        }
    }
    test("failed proof persists lockout and erases both secrets without credential mutation") {
        CredentialFixture().use { f ->
            repeat(5) {
                val runtime = f.runtime
                val proof = "wrong-passphrase".toCharArray()
                val replacement = f.newPassword.toCharArray()
                shouldThrow<AuthenticationDeniedException> {
                    runtime.rotate(runtime.begin(f.handle), proof, replacement)
                }
                proof.all { it == '\u0000' } shouldBe true
                replacement.all { it == '\u0000' } shouldBe true
            }
            f.reopen()
            f.credential().failedAttempts shouldBe 5
            shouldThrow<AuthenticationDeniedException> { f.login() }
            f.credential().revision shouldBe 1L
            f.history().size shouldBe 1
            f.now = f.now.plusSeconds(61)
            f.login().person shouldBe f.person
        }
    }
    test("audit failure rolls back verifier rotation and revocation") {
        CredentialFixture().use { f ->
            f.driver.execute(null, "CREATE TRIGGER fail_audit BEFORE INSERT ON local_credential_audit " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END", 0)
            val runtime = f.runtime
            shouldThrow<Exception> {
                runtime.rotate(runtime.begin(f.handle), f.oldPassword.toCharArray(), f.newPassword.toCharArray())
            }
            shouldThrow<Exception> { runtime.revoke(runtime.begin(f.handle), f.oldPassword.toCharArray()) }
            f.reopen()
            f.credential().revision shouldBe 1L
            f.credential().revokedAt shouldBe null
            f.history().size shouldBe 1
            f.login().person shouldBe f.person
            shouldThrow<AuthenticationDeniedException> { f.login(f.newPassword) }
        }
    }
    test("rights denial consumes proof and leaves credential unchanged") {
        CredentialFixture().use { f ->
            val service = f.service()
            val lifecycle = LocalCredentialLifecycleService(service, f.mutations, f.provider,
                RightsConstraint { throw RightsConstraintViolation("denied", "test policy") }, Clock { f.now })
            val challenge = service.begin(f.unit, f.provider.providerId, f.handle)
            shouldThrow<RightsConstraintViolation> {
                lifecycle.revoke(f.unit, challenge, f.oldPassword.toCharArray())
            }
            shouldThrow<AuthenticationDeniedException> {
                lifecycle.revoke(f.unit, challenge, f.oldPassword.toCharArray())
            }
            f.credential().revision shouldBe 1L
            f.history().size shouldBe 1
        }
    }
    test("invalid replacement and challenge from another runtime or unit cannot mutate credentials") {
        CredentialFixture().use { f ->
            val runtime = f.runtime
            val replacement = "short".toCharArray()
            shouldThrow<IllegalArgumentException> {
                runtime.rotate(runtime.begin(f.handle), f.oldPassword.toCharArray(), replacement)
            }
            replacement.all { it == '\u0000' } shouldBe true
            shouldThrow<IllegalArgumentException> {
                runtime.rotate(runtime.begin(f.handle), f.oldPassword.toCharArray(), f.oldPassword.toCharArray())
            }
            shouldThrow<AuthenticationDeniedException> {
                f.runtime.revoke(runtime.begin(f.handle), f.oldPassword.toCharArray())
            }
            val otherUnit = sotaos.api.identity.P01CredentialsRuntime(f.store, SotaId("other"), Clock { f.now })
            shouldThrow<AuthenticationDeniedException> {
                otherUnit.revoke(otherUnit.begin(f.handle), f.oldPassword.toCharArray())
            }
            f.credential().revision shouldBe 1L
            f.login().person shouldBe f.person
        }
    }
})
