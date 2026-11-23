package sotaos.application.services

import sotaos.domain.agency.Mission
import sotaos.domain.shared.SubjectRef

/** Version describes this snapshot format, not a numeric Mission revision or governance mandate. */
object MissionSnapshot {
    fun values(mission: Mission): Map<String, Any?> {
        val (kind, id) = when (val owner = mission.owner) {
            is SubjectRef.Person -> "PERSON" to owner.id.value
            is SubjectRef.Core -> "CORE" to owner.id.value
            is SubjectRef.Sota -> "SOTA" to owner.id.value
            is SubjectRef.Agent -> "AGENT" to owner.id.value
        }
        return mapOf("snapshotVersion" to 1L, "id" to mission.id.value, "ownerKind" to kind, "ownerId" to id,
            "intent" to mission.intent, "objectives" to mission.objectives.toList(),
            "participants" to mission.participants.map { it.value }, "mode" to mission.mode.name,
            "state" to mission.state.name)
    }
}
