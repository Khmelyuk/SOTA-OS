package sotaos.api.sync

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant

enum class P09CertificateState { VALID, RENEW_SOON, NOT_YET_VALID, EXPIRED }

/** Public leaf metadata only. A renewal warning is advisory, not a trust or issuance decision. */
data class P09CertificateInfo(
    val alias: String,
    val sha256: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val observedAt: Instant,
    val secondsRemaining: Long,
    val state: P09CertificateState
)

internal fun certificateInfo(alias: String, certificate: X509Certificate, at: Instant): P09CertificateInfo {
    val start = certificate.notBefore.toInstant()
    val end = certificate.notAfter.toInstant()
    val remaining = Duration.between(at, end)
    val state = when {
        at.isBefore(start) -> P09CertificateState.NOT_YET_VALID
        at.isAfter(end) -> P09CertificateState.EXPIRED
        remaining <= Duration.ofDays(RENEWAL_WARNING_DAYS) -> P09CertificateState.RENEW_SOON
        else -> P09CertificateState.VALID
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
    return P09CertificateInfo(alias, digest, start, end, at, remaining.seconds, state)
}

const val RENEWAL_WARNING_DAYS = 30L
