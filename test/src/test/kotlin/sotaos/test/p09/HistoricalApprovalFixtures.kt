package sotaos.test.p09

import sotaos.application.ports.*
import sotaos.domain.shared.Scope
import sotaos.domain.sync.SyncRecord
import sotaos.persistence.SqlDelightHistoricalApprovalRepository
import sotaos.persistence.SqlDelightVerifiedRecordRepository
import sotaos.security.*

internal fun ProductionFixture.enrollCurrentKeyOnly(): String {
    runtime.keyProvisioning.enroll(invocation, authorityId,
        ActorSigningKeyRecord(remoteActor, "current-key", Ed25519EventSigner.generate().publicKeyEncoded()))
    return runtime.peerProvisioning.enroll(invocation, authorityId, policy)
}

internal fun ProductionFixture.authorizeHistory(record: SyncRecord) {
    repositories.authorities.save(authority.copy(scope = Scope(
        authority.scope.actions + setOf("history.approve", "history.revoke"),
        authority.scope.resources + historicalRecordTarget(record))))
}

internal fun ProductionFixture.oldKey() =
    ActorSigningKeyRecord(remoteActor, "historical-key", signer.publicKeyEncoded())
internal fun ProductionFixture.approvals() = SqlDelightHistoricalApprovalRepository(store.database)
internal fun ProductionFixture.receipts() = SqlDelightVerifiedRecordRepository(store.database)
internal fun ProductionFixture.approveHistory(record: SyncRecord = record()): HistoricalRecordApproval {
    authorizeHistory(record)
    return runtime.historicalApprovals.approve(invocation, authorityId, record, oldKey(), "review:offline-archive-1")
}
