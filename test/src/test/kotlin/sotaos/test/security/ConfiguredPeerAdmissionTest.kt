package sotaos.test.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import sotaos.application.sync.SyncAdmission
import sotaos.application.sync.SyncDirection
import sotaos.domain.shared.SotaId
import sotaos.security.ConfiguredPeerAdmission
import sotaos.test.sync.record

class ConfiguredPeerAdmissionTest : FunSpec({
    test("only explicitly trusted peers reach record admission") {
        val peer = SotaId("trusted")
        val policy = ConfiguredPeerAdmission(setOf(peer), allowAll)
        policy.check(peer, SyncDirection.IMPORT, listOf(record("event", peer)))
        shouldThrow<IllegalArgumentException> {
            policy.check(SotaId("unknown"), SyncDirection.IMPORT, emptyList())
        }
    }

    test("a deployment can restrict admission to one direction") {
        val peer = SotaId("trusted")
        val policy = ConfiguredPeerAdmission(setOf(peer), allowAll, setOf(SyncDirection.IMPORT))
        shouldThrow<IllegalArgumentException> { policy.check(peer, SyncDirection.EXPORT, emptyList()) }
    }
})

private val allowAll = SyncAdmission { _, _, _ -> }
