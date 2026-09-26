package sotaos.api.exit

import sotaos.application.ports.*
import sotaos.application.services.*
import sotaos.domain.shared.*
import sotaos.persistence.*
import sotaos.security.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

/** Trusted local host must authenticate invocation.actor before calling this composition root. */
class P10Runtime(
    store: SqlDelightStore,
    clock: Clock,
    ids: IdGenerator,
    rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))
) {
    private val exits = SqlDelightExitRepository(store.database)
    private val scope = SqlDelightExitScopeRepository(store.database)
    val service = ExitService(exits, scope, clock, ids, rights)
    val governance = ExitGovernanceService(exits, SqlDelightExitInventoryRepository(store.database),
        SqlDelightRepositories(store.database).authorities, clock, rights)

    /** Each stage survives restart. A failed delivery never terminates membership. */
    fun leave(invocation: ProtocolInvocation, core: CoreId, destination: Path): ExitReceipt {
        val process = service.requestExit(invocation, core)
        service.revokeActiveDelegations(invocation, process.id)
        service.closeRelations(invocation, process.id)
        service.settleObligations(invocation, process.id)
        val document = service.exportPortableData(invocation, process.id)
        deliverExitDocument(document, destination)
        return service.terminateParticipation(invocation, process.id, document.sha256)
    }
}

/** Create a private file without replacing an existing path or following its final symlink. */
fun deliverExitDocument(document: ExitDocument, destination: Path) {
    val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    FileChannel.open(destination, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
        permissions).use { channel ->
        val bytes = ByteBuffer.wrap(document.json.toByteArray(Charsets.UTF_8))
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }
    FileChannel.open(destination.toAbsolutePath().parent, StandardOpenOption.READ).use { it.force(true) }
}
