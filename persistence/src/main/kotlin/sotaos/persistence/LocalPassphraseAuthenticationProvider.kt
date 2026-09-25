package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.shared.PersonId
import sotaos.domain.shared.SotaId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local CLI adapter. This verifies a passphrase but never establishes a civil/legal identity. */
class LocalPassphraseAuthenticationProvider(
    private val repository: AuthenticationRepository,
    private val random: SecureRandom = SecureRandom()
) : AuthenticationProvider {
    override val providerId: String = PROVIDER_ID

    override fun begin(unit: SotaId, subjectHint: String?, now: Instant): AuthenticationChallenge {
        val handle = subjectHint?.let(SqlDelightAuthenticationRepository::normalizeHandle)
            ?: throw IllegalArgumentException("Local authentication requires a login handle.")
        require(handle.isNotBlank() && handle.length <= MAX_HANDLE_LENGTH &&
            handle.none { Character.isISOControl(it) }) {
            "Local authentication requires a valid login handle."
        }
        return AuthenticationChallenge(
            id = UUID.randomUUID().toString(),
            unit = unit,
            providerId = providerId,
            subjectHint = handle,
            instructions = "Enter the local passphrase for handle '$handle'.",
            verificationUri = null,
            expiresAt = now.plus(CHALLENGE_LIFETIME)
        )
    }

    override fun complete(challenge: AuthenticationChallenge, proof: CharArray, now: Instant): AuthenticatedPrincipal? {
        val hintedHandle = challenge.subjectHint
        return if (challenge.providerId != providerId || hintedHandle.isNullOrBlank()) {
            null
        } else {
            val handle = SqlDelightAuthenticationRepository.normalizeHandle(hintedHandle)
            val credential = repository.findLocalCredential(challenge.unit, handle)
            if (credential?.lockedUntil?.isAfter(now) == true) null
            else verifyPassphrase(challenge.unit, handle, credential, proof, now)
        }
    }

    private fun verifyPassphrase(
        unit: SotaId,
        handle: String,
        credential: LocalCredentialRecord?,
        proof: CharArray,
        now: Instant
    ): AuthenticatedPrincipal? {
        val dummySalt = ByteArray(SALT_BYTES) { DUMMY_SALT_BYTE }
        val salt = credential?.let { decode(it.saltBase64, SALT_BYTES) } ?: dummySalt
        val expected = credential?.let { decode(it.hashBase64, HASH_BYTES) } ?: ByteArray(HASH_BYTES)
        val iterations = credential?.iterations?.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS) ?: PBKDF2_ITERATIONS
        val actual = derive(proof, salt, iterations)
        val validHash = MessageDigest.isEqual(actual, expected)
        actual.fill(0); expected.fill(0); salt.fill(0)

        if (credential == null || !validHash) {
            if (credential != null) repository.recordLocalFailure(unit, handle, now.plus(LOCK_DURATION))
            return null
        }
        repository.clearLocalFailures(unit, handle)
        return AuthenticatedPrincipal(providerId, handle)
    }

    /** Bootstrap/link operation for a trusted local operator; callers must verify the Person first. */
    fun enroll(unit: SotaId, handle: String, person: PersonId, passphrase: CharArray, now: Instant) {
        var salt: ByteArray? = null
        var hash: ByteArray? = null
        try {
            val normalized = SqlDelightAuthenticationRepository.normalizeHandle(handle)
            require(normalized.isNotBlank() && normalized.length <= MAX_HANDLE_LENGTH &&
                normalized.none { Character.isISOControl(it) }) {
                "Login handle is invalid."
            }
            require(passphrase.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
                "Passphrase must contain 12 to 1024 characters."
            }
            require(repository.findLocalCredential(unit, normalized) == null) { "Local handle is already enrolled." }
            salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            hash = derive(passphrase, salt, PBKDF2_ITERATIONS)
            repository.enrollLocalCredential(
                unit, normalized, person, Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash), PBKDF2_ITERATIONS, now
            )
        } finally {
            salt?.fill(0); hash?.fill(0); passphrase.fill('\u0000')
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, HASH_BYTES * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decode(value: String, expectedLength: Int): ByteArray = try {
        Base64.getDecoder().decode(value).also { require(it.size == expectedLength) }
    } catch (_: Exception) {
        ByteArray(expectedLength)
    }

    companion object {
        const val PROVIDER_ID = "local-passphrase"
        const val PBKDF2_ITERATIONS = 600_000
        private const val MIN_ITERATIONS = 100_000
        private const val MAX_ITERATIONS = 2_000_000
        private const val DUMMY_SALT_BYTE: Byte = 0x5a
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val MIN_PASSWORD_LENGTH = 12
        private const val MAX_PASSWORD_LENGTH = 1024
        private const val MAX_HANDLE_LENGTH = 128
        private val CHALLENGE_LIFETIME: Duration = Duration.ofMinutes(2)
        private val LOCK_DURATION: Duration = Duration.ofMinutes(1)
    }
}
