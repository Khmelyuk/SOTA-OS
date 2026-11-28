package sotaos.application.services

import sotaos.application.ports.*
import sotaos.domain.shared.*
import java.security.MessageDigest

/** Reads only an existing frozen archive. The host authenticates the invoking Person. */
class ExitArchiveService(private val exits: ExitRepository, private val rights: RightsConstraint) {
    fun read(invocation: ProtocolInvocation, core: CoreId, exitId: String): ExitDocument = exits.transaction {
        val person = requireNotNull(invocation.actor as? SubjectRef.Person) {
            "Only a Person may read own exit archive."
        }
        val target = ExitTarget(person.id, core)
        rights.check(invocation, "P10", "readArchive", SubjectRef.Core(core),
            scope = Scope(setOf("readArchive"), setOf("core:${core.value}", "exit:$exitId")))
        val process = exits.find(exitId)
        require(process != null && process.target == target) { "No exit archive for this Person/Core/exit." }
        require(process.stage == ExitStage.EXPORT_READY || process.stage == ExitStage.COMPLETED) {
            "The exit has no prepared archive."
        }
        val document = requireNotNull(exits.export(exitId)) { "The stored exit archive is missing." }
        require(document.exitId == exitId && document.target == target) { "Stored exit archive target mismatch." }
        val digest = MessageDigest.getInstance("SHA-256").digest(document.json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(digest == document.sha256) { "Stored exit archive checksum mismatch." }
        document
    }
}
