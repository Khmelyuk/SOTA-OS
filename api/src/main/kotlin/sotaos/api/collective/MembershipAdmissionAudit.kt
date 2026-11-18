package sotaos.api.collective

import sotaos.application.ports.*
import sotaos.domain.collective.Membership
import sotaos.domain.memory.Event
import sotaos.domain.shared.*
import sotaos.domain.sync.SyncRecord
import sotaos.protocol.JsonSyncRecordCodec
import sotaos.security.SyncRecordIntegrity

/** Local R0/R1 audit, not a signed P09 producer or a remote collective mandate. */
internal class MembershipAdmissionAudit(
    private val events: EventStore,
    private val unit: SotaId,
    private val context: Context,
    private val clock: Clock,
    private val ids: IdGenerator
) {
    private val integrity = SyncRecordIntegrity(JsonSyncRecordCodec())

    fun append(session: AuthenticatedSession, membership: Membership, purpose: String,
        authority: AuthorityId?, parent: EventId? = null): EventId {
        val actor = SubjectRef.Person(session.person)
        val now = clock.now()
        val event = Event(EventId(ids.next()), if (parent == null) INVITED else "MEMBERSHIP_ACCEPTED", actor,
            now, context, authority?.value, null,
            mapOf("version" to "1", "membership" to snapshot(membership), "unitId" to unit.value,
                "purpose" to purpose, "providerId" to session.providerId), null,
            Provenance(parent, actor, now, listOfNotNull(parent?.value)), "", null)
        events.append(event.copy(contentHash = integrity.hash(SyncRecord(event, unit))))
        return event.id
    }

    fun source(invitation: RecordedInvitation): Event {
        val source = requireNotNull(events.findById(invitation.auditEvent)) { "Invitation audit is missing." }
        require(source.type == INVITED && source.context == context && source.actor is SubjectRef.Person)
        require(source.payload["version"] == "1" && source.payload["unitId"] == unit.value)
        require(source.payload["membership"] == snapshot(invitation.membership)) { "Invitation terms differ." }
        require(!source.timestamp.isAfter(clock.now()) && source.provenance.author == source.actor)
        integrity.check(SyncRecord(source, unit))
        return source
    }

    private fun snapshot(membership: Membership): Map<String, String?> = mapOf(
        "person" to membership.subject.value,
        "core" to (membership.collective as? SubjectRef.Core)?.id?.value,
        "role" to membership.role?.value, "start" to membership.start.toString(),
        "end" to membership.end?.toString(), "state" to membership.state.name
    )

    companion object {
        const val INVITED = "MEMBERSHIP_INVITED"
    }
}
