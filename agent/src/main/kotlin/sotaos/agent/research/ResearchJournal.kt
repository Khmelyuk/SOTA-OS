package sotaos.agent.research

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/** Local bounded journal; each batch and its audit commit through one atomic replacement. */
class ResearchJournal(directory: Path) {
    private val root = directory.toAbsolutePath().normalize()
    private val journal = root.resolve("research.jsonl")
    private val lockPath = root.resolve("research.lock")

    init {
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root))
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
    }

    internal fun <T> access(commit: (() -> Unit) -> Unit, block: (MutableList<JsonObject>) -> T): T =
        synchronized(locks.computeIfAbsent(root) { Any() }) {
            require(!Files.isSymbolicLink(lockPath) && !Files.isSymbolicLink(journal))
            FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    val records = read()
                    val original = records.toList()
                    val result = block(records)
                    require(records.take(original.size) == original) { "History cannot be rewritten." }
                    commit { if (records != original) write(records) }
                    result
                }
            }
        }

    private fun read(): MutableList<JsonObject> {
        if (!Files.exists(journal)) return mutableListOf()
        require(Files.size(journal) <= MAX_JOURNAL)
        val text = Files.readString(journal)
        require(text.isEmpty() || text.endsWith('\n')) { "Incomplete research journal; manual recovery required." }
        var previous = "GENESIS"
        return text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val record = Json.parseToJsonElement(line).jsonObject
            require(record.field("previous") == previous)
            val unsigned = JsonObject(record.filterKeys { it != "hash" })
            require(digest(unsigned.toString()) == record.field("hash")) { "Research journal integrity failure." }
            previous = record.field("hash")
            record
        }.toMutableList()
    }

    private fun write(records: List<JsonObject>) {
        val bytes = records.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JOURNAL)
        val temporary = Files.createTempFile(root, "research-", ".tmp")
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root, READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_JOURNAL = 16 * 1024 * 1024
        private val locks = ConcurrentHashMap<Path, Any>()
    }
}
