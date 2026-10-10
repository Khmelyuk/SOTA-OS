package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.shared.*
import sotaos.security.*

class CredentialRaceTest : FunSpec({
    test("revocation during proof verification cannot return an authenticated session") {
        CredentialFixture().use { f ->
            val repository = object : AuthenticationRepository by f.authentication {
                override fun confirmLocalCredential(unit: SotaId, handle: String, revision: Long): Boolean {
                    f.mutations.revoke(LocalCredentialChange(unit, handle, f.person, revision, f.now))
                    return f.authentication.confirmLocalCredential(unit, handle, revision)
                }
            }
            val service = f.service(repository)
            shouldThrow<AuthenticationDeniedException> {
                service.complete(f.unit, service.begin(f.unit, f.provider.providerId, f.handle),
                    f.oldPassword.toCharArray())
            }
            f.credential().revokedAt shouldBe f.now
        }
    }
    test("rotation after authentication rejects the stale lifecycle change and preserves new verifier") {
        CredentialFixture().use { f ->
            val authentication = f.service()
            val encoder = LocalCredentialEncoder { secret ->
                f.mutations.rotate(LocalCredentialChange(f.unit, f.handle, f.person, 1, f.now),
                    f.provider.encode(f.newPassword.toCharArray()))
                f.provider.encode(secret)
            }
            val lifecycle = LocalCredentialLifecycleService(authentication, f.mutations, encoder,
                RightsConstraintDecorator(listOf(NoAgentActionRule)), Clock { f.now })
            shouldThrow<IllegalArgumentException> {
                lifecycle.rotate(f.unit, authentication.begin(f.unit, f.provider.providerId, f.handle),
                    f.oldPassword.toCharArray(), "stale-replacement-secret".toCharArray())
            }
            f.login(f.newPassword).person shouldBe f.person
            f.history().map { it.operation } shouldBe listOf("ENROLL", "ROTATE")
            f.authentication.recordLocalFailure(f.unit, f.handle, 1, f.now.plusSeconds(60))
            f.credential().failedAttempts shouldBe 0
        }
    }
    test("mutation adapter rejects wrong owner and audit cannot be rewritten") {
        CredentialFixture().use { f ->
            shouldThrow<IllegalArgumentException> {
                f.mutations.revoke(LocalCredentialChange(f.unit, f.handle, PersonId("other"), 1, f.now))
            }
            shouldThrow<Exception> { f.driver.execute(null, "DELETE FROM local_credential_audit", 0) }
            shouldThrow<Exception> {
                f.driver.execute(null, "UPDATE local_credential_audit SET operation = 'REVOKE'", 0)
            }
            f.credential().revision shouldBe 1L
        }
    }
})
