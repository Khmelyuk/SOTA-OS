package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.ports.*
import sotaos.application.services.AuthenticationDeniedException
import sotaos.application.services.AuthenticationService
import sotaos.domain.shared.*
import sotaos.persistence.LocalPassphraseAuthenticationProvider
import java.time.Instant

class AuthenticationVerificationTest : FunSpec({
    val unitA = SotaId("unit-a")
    val unitB = SotaId("unit-b")
    val providerId = "test-external"
    val now = Instant.parse("2026-08-27T00:00:00Z")

    class IdentityMap : AuthenticationRepository {
        val bindings = mutableMapOf<Triple<SotaId, String, String>, PersonId>()
        override fun findPerson(unit: SotaId, providerId: String, providerSubject: String) = bindings[Triple(unit,
            providerId, providerSubject)]
        override fun bind(unit: SotaId, providerId: String, providerSubject: String, person: PersonId,
            linkedAt: Instant) {
            bindings[Triple(unit, providerId, providerSubject)] = person
        }
        override fun findLocalCredential(unit: SotaId, handle: String): LocalCredentialRecord? = null
        override fun enrollLocalCredential(unit: SotaId, handle: String, person: PersonId, saltBase64: String,
            hashBase64: String, iterations: Int, createdAt: Instant) = Unit
        override fun recordLocalFailure(unit: SotaId, handle: String, lockedUntil: Instant) = Unit
        override fun clearLocalFailures(unit: SotaId, handle: String) = Unit
    }

    class LocalIdentityMap : AuthenticationRepository {
        private val people = mutableMapOf<Triple<SotaId, String, String>, PersonId>()
        private val credentials = mutableMapOf<Pair<SotaId, String>, LocalCredentialRecord>()
        override fun findPerson(unit: SotaId, providerId: String, providerSubject: String) =
            people[Triple(unit, providerId, providerSubject)]
        override fun bind(unit: SotaId, providerId: String, providerSubject: String, person: PersonId,
            linkedAt: Instant) {
            people[Triple(unit, providerId, providerSubject)] = person
        }
        override fun findLocalCredential(unit: SotaId, handle: String) = credentials[unit to handle]
        override fun enrollLocalCredential(
            unit: SotaId, handle: String, person: PersonId, saltBase64: String, hashBase64: String,
            iterations: Int, createdAt: Instant
        ) {
            bind(unit, LocalPassphraseAuthenticationProvider.PROVIDER_ID, handle, person, createdAt)
            credentials[unit to handle] = LocalCredentialRecord(saltBase64, hashBase64, iterations, 0, null)
        }
        override fun recordLocalFailure(unit: SotaId, handle: String, lockedUntil: Instant) {
            val old = credentials.getValue(unit to handle)
            credentials[unit to handle] = old.copy(
                failedAttempts = old.failedAttempts + 1,
                lockedUntil = if (old.failedAttempts + 1 >= 5) lockedUntil else old.lockedUntil
            )
        }
        override fun clearLocalFailures(unit: SotaId, handle: String) {
            credentials[unit to handle]?.let { credentials[unit to handle] = it.copy(failedAttempts = 0,
                lockedUntil = null) }
        }
    }

    fun provider(succeeds: Boolean = true): Pair<AuthenticationProvider, MutableList<Int>> {
        val attempts = mutableListOf<Int>()
        return object : AuthenticationProvider {
            override val providerId = "test-external"
            override fun begin(unit: SotaId, subjectHint: String?, now: Instant) = AuthenticationChallenge(
                "challenge-${attempts.size}", unit, providerId, subjectHint, "verify", null, now.plusSeconds(60)
            )
            override fun complete(challenge: AuthenticationChallenge, proof: CharArray,
                now: Instant): AuthenticatedPrincipal? {
                attempts += 1
                if (!succeeds || proof.concatToString() != "valid-proof") return null
                return AuthenticatedPrincipal(providerId, "external-subject-1")
            }
        } to attempts
    }

    test("correct provider authentication resolves to the linked stable Person") {
        val identities = IdentityMap().apply { bind(unitA, providerId, "external-subject-1", PersonId("person-a"),
            now) }
        val (provider) = provider()
        val service = AuthenticationService(listOf(provider), identities, mapOf(unitA to setOf(providerId)), { now })
        val challenge = service.begin(unitA, providerId)
        service.complete(unitA, challenge, "valid-proof".toCharArray()).person shouldBe PersonId("person-a")
    }

    test("repeated unsuccessful authentication attempts never create a session") {
        val (provider, attempts) = provider(succeeds = false)
        val service = AuthenticationService(listOf(provider), IdentityMap(), mapOf(unitA to setOf(providerId)), { now })
        repeat(3) {
            val challenge = service.begin(unitA, providerId)
            shouldThrow<AuthenticationDeniedException> { service.complete(unitA, challenge, "bad-proof".toCharArray()) }
        }
        attempts.size shouldBe 3
    }

    test("provider subject linked in another Sota unit cannot authenticate here") {
        val identities = IdentityMap().apply { bind(unitA, providerId, "external-subject-1", PersonId("person-a"),
            now) }
        val (provider) = provider()
        val service = AuthenticationService(listOf(provider), identities, mapOf(unitA to setOf(providerId),
            unitB to setOf(providerId)), { now })
        val challenge = service.begin(unitB, providerId)
        shouldThrow<AuthenticationDeniedException> { service.complete(unitB, challenge, "valid-proof".toCharArray()) }
    }

    test("provider not enabled for the Sota unit is rejected before challenge") {
        val (provider) = provider()
        val service = AuthenticationService(listOf(provider), IdentityMap(), mapOf(unitA to emptySet()), { now })
        shouldThrow<AuthenticationDeniedException> { service.begin(unitA, providerId) }
    }

    test("challenge is one-use and cannot be replayed") {
        val identities = IdentityMap().apply { bind(unitA, providerId, "external-subject-1", PersonId("person-a"),
            now) }
        val (provider) = provider()
        val service = AuthenticationService(listOf(provider), identities, mapOf(unitA to setOf(providerId)), { now })
        val challenge = service.begin(unitA, providerId)
        service.complete(unitA, challenge, "valid-proof".toCharArray())
        shouldThrow<AuthenticationDeniedException> { service.complete(unitA, challenge, "valid-proof".toCharArray()) }
    }

    test("local passphrase accepts the right proof, rejects repeated failures, then unlocks after timeout") {
        var clock = now
        val repository = LocalIdentityMap()
        val local = LocalPassphraseAuthenticationProvider(repository)
        val person = PersonId("local-person")
        local.enroll(unitA, " local-user ", person, "verification-passphrase".toCharArray(), now)
        val service = AuthenticationService(listOf(local), repository,
            mapOf(unitA to setOf(LocalPassphraseAuthenticationProvider.PROVIDER_ID)), { clock })

        repeat(5) {
            val challenge = service.begin(unitA, LocalPassphraseAuthenticationProvider.PROVIDER_ID, "local-user")
            shouldThrow<AuthenticationDeniedException> {
                service.complete(unitA, challenge, "incorrect-passphrase".toCharArray())
            }
        }
        val lockedChallenge = service.begin(unitA, LocalPassphraseAuthenticationProvider.PROVIDER_ID, "local-user")
        shouldThrow<AuthenticationDeniedException> {
            service.complete(unitA, lockedChallenge, "verification-passphrase".toCharArray())
        }

        clock = now.plusSeconds(61)
        val proof = "verification-passphrase".toCharArray()
        service.complete(unitA, service.begin(unitA, LocalPassphraseAuthenticationProvider.PROVIDER_ID,
            "local-user"), proof)
            .person shouldBe person
        proof.all { it == '\u0000' } shouldBe true
    }
})
