package sotaos.api.exit

import sotaos.application.ports.ExitDocument
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

/** Local file observation, not a persistent delivery receipt or a signature verification. */
internal fun verifyExitDocument(document: ExitDocument, file: Path) {
    require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Archive verification requires an existing regular file." }
    val expectedSize = document.json.toByteArray(Charsets.UTF_8).size.toLong()
    FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
        require(channel.size() == expectedSize) { "Archive file size mismatch." }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        var count = 0L
        // Read at most the expected byte count plus one, even if a file grows during verification.
        buffer.limit(minOf(buffer.capacity().toLong(), expectedSize + 1).toInt())
        var read = channel.read(buffer)
        while (read != -1) {
            count += read
            require(count <= expectedSize) { "Archive file size mismatch." }
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), expectedSize - count + 1).toInt())
            read = channel.read(buffer)
        }
        require(count == expectedSize) { "Archive file size mismatch." }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(sha256 == document.sha256) { "Archive file checksum mismatch." }
    }
}
