package sotaos.persistence

import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.time.Instant

class SqlDelightExitRepository(private val db: SotaOsDatabase) : ExitRepository {
    override fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }
    override fun find(id: String): ExitProcess? = db.exitQueries.findExit(id).executeAsOneOrNull()?.toDomain()
    override fun findOpen(target: ExitTarget): ExitProcess? = db.exitQueries
        .findOpenExit(target.person.value, target.core.value).executeAsOneOrNull()?.toDomain()
    override fun save(process: ExitProcess) {
        if (find(process.id) == null) {
            db.exitQueries.insertExit(process.id, process.target.person.value, process.target.core.value,
                process.requestedAt.toString(), process.updatedAt.toString(), process.stage.name)
        } else {
            db.exitQueries.updateExit(process.stage.name, process.updatedAt.toString(), process.id)
        }
    }
    override fun appendTransition(transition: ExitTransition) {
        db.exitQueries.insertTransition(transition.exitId, transition.stage.name,
            transition.actor.value, transition.occurredAt.toString())
        appendExitEvent(db, requireNotNull(find(transition.exitId)), transition)
    }
    override fun transitions(id: String): List<ExitTransition> = db.exitQueries.selectTransitions(id)
        .executeAsList().map { ExitTransition(it.exit_id, ExitStage.valueOf(it.stage), PersonId(it.actor_id),
            Instant.parse(it.occurred_at)) }
    override fun prepareExport(process: ExitProcess): ExitDocument {
        val document = ExitArchiveMapping(db).create(process)
        db.exitQueries.insertArchive(process.id, document.json, document.sha256)
        return document
    }
    override fun export(id: String): ExitDocument? = db.exitQueries.selectArchive(id).executeAsOneOrNull()?.let {
        ExitDocument(id, requireNotNull(find(id)).target, it.document_json, it.sha256)
    }
}

private fun Exit_process.toDomain(): ExitProcess = ExitProcess(
    exit_id, ExitTarget(PersonId(person_id), CoreId(core_id)), Instant.parse(requested_at),
    Instant.parse(updated_at), ExitStage.valueOf(stage)
)
