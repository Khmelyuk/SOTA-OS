package sotaos.persistence

import sotaos.application.ports.ExitEventRecorder
import sotaos.domain.memory.Event
import sotaos.domain.shared.EventId
import sotaos.persistence.db.SotaOsDatabase

/** Never silently downgrade an already signed exit after a host restart. */
class LocalExitEventRecorder(db: SotaOsDatabase) : ExitEventRecorder {
    private val events = SqlDelightEventStore(db)
    override fun append(event: Event, parents: Set<EventId>) {
        parents.forEach { id ->
            require(requireNotNull(events.findById(id)).signature == null) {
                "Resume this signed exit with its signing configuration."
            }
        }
        events.append(event)
    }
}
