package sotaos.test.rehearsal

import sotaos.application.ports.ExitReceipt
import sotaos.application.ports.ExitStage
import sotaos.application.sync.PeerCheckpoint
import sotaos.domain.collective.Core
import sotaos.domain.shared.SubjectRef
import sotaos.persistence.SqlDelightExitRepository
import sotaos.persistence.SqlDelightExitScopeRepository
import sotaos.persistence.SqlDelightSyncRepository
import sotaos.protocol.JsonSyncRecordCodec
import java.sql.DriverManager

internal fun checkJournal(node: RehearsalNode, other: RehearsalNode, count: Int, sent: Long = count.toLong()) =
    node.access { store, _ ->
        val journal = SqlDelightSyncRepository(store.database, JsonSyncRecordCodec())
        check(journal.records().size == count)
        check(journal.records().map { it.event.id }.toSet().size == count)
        check(journal.checkpoint(other.id) == PeerCheckpoint(sent, count.toLong()))
        journal.records()
    }

internal fun verifyFinalState(a: RehearsalNode, b: RehearsalNode, core: Core, receipt: ExitReceipt) {
    val records = checkJournal(a, b, FINAL_RECORDS)
    check(records == checkJournal(b, a, FINAL_RECORDS))
    records.forEach { record ->
        check(record.origin == a.id && record.event.actor == a.actor)
        check(a.signer.verify(record.event.contentHash, requireNotNull(record.event.signature)))
    }
    a.localAccess { store, repositories ->
        val exits = SqlDelightExitRepository(store.database)
        check(exits.find(receipt.exitId)?.stage == ExitStage.COMPLETED)
        check(exits.transitions(receipt.exitId).map { it.stage } == ExitStage.entries)
        check(exits.export(receipt.exitId)?.sha256 == receipt.exportSha256)
        check(repositories.persons.findById(a.person.id) == a.person)
        check(repositories.identities.findByPerson(a.person.id).size == 1)
        check(repositories.memberships.findActiveFor(b.person.id, SubjectRef.Core(core.id)) != null)
    }
    b.localAccess { store, repositories ->
        val scope = SqlDelightExitScopeRepository(store.database)
        check(scope.hasMembership(receipt.target) && scope.hasRelations(receipt.target))
        check(scope.hasUnresolvedObligations(receipt.target))
        check(SqlDelightExitRepository(store.database).find(receipt.exitId) == null)
        check(repositories.events.findByActor(a.actor).size == FINAL_RECORDS)
    }
    checkBusinessCounts(a, 1)
    checkBusinessCounts(b, 0)
}

private fun checkBusinessCounts(node: RehearsalNode, expected: Long) {
    DriverManager.getConnection("jdbc:sqlite:${node.path}").use { connection ->
        connection.createStatement().use { statement ->
            listOf("action", "result", "experience", "knowledge").forEach { table ->
                check(countRows(statement, table) == expected) { "Unexpected $table count on ${node.name}" }
            }
        }
    }
}

private fun countRows(statement: java.sql.Statement, table: String): Long =
    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
        check(rows.next())
        rows.getLong(1)
    }

private const val FINAL_RECORDS = 7
