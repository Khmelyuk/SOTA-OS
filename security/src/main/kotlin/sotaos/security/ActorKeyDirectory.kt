package sotaos.security

import sotaos.domain.shared.SubjectRef
import sotaos.application.ports.ActorSigningKeyRecord
import java.security.PublicKey

class ActorKeyDirectory(initial: Map<SubjectRef, ActorKey> = emptyMap()) {
    private val active = initial.toMutableMap()

    fun enroll(actor: SubjectRef, key: ActorKey) {
        require(active.putIfAbsent(actor, key) == null) {
            "An active signing key already exists for actor ${actor.value()}."
        }
    }

    fun rotate(actor: SubjectRef, key: ActorKey) {
        require(active.containsKey(actor)) { "No active signing key exists for actor ${actor.value()}." }
        active[actor] = key
    }

    fun revoke(actor: SubjectRef, keyId: String) {
        val current = active[actor]
        require(current?.id == keyId) { "Key $keyId is not the active key for actor ${actor.value()}." }
        active.remove(actor)
    }

    fun signerFor(actor: SubjectRef): EventSigner? = active[actor]?.let { Ed25519EventSigner(it.publicKey) }
    fun activeKeys(): Map<SubjectRef, ActorKey> = active.toMap()

    companion object {
        fun fromRecords(records: Iterable<ActorSigningKeyRecord>): ActorKeyDirectory = ActorKeyDirectory(
            records.associate { record ->
                record.actor to ActorKey(record.keyId, Ed25519EventSigner
                    .fromPublicKeyEncoded(record.publicKeyBase64).publicKey())
            }
        )
    }
}

data class ActorKey(val id: String, val publicKey: PublicKey) {
    init { require(id.isNotBlank()) { "Actor key ID must not be blank." } }
}

private fun SubjectRef.value(): String = when (this) {
    is SubjectRef.Person -> id.value
    is SubjectRef.Core -> id.value
    is SubjectRef.Sota -> id.value
    is SubjectRef.Agent -> id.value
}
