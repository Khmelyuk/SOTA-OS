package sotaos.api.cli

import kotlinx.serialization.json.*
import sotaos.agent.research.PublicDiscussion
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/** Bounded, explicit local public-discussion interchange; never executes source content. */
internal object ResearchFiles {
    private const val MAX_DISCUSSIONS = 20
    private const val MAX_INPUT = 1024 * 1024
    private val fields = setOf("url", "externalId", "author", "publishedAt", "title", "text")

    fun text(path: Path): String {
        require(Files.isRegularFile(path) && Files.size(path) <= MAX_INPUT) { "Input must be a file up to 1 MiB." }
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(MAX_INPUT + 1)
            require(bytes.size <= MAX_INPUT)
            return String(bytes, Charsets.UTF_8)
        }
    }

    fun discussions(path: Path): List<PublicDiscussion> {
        val root = Json.parseToJsonElement(text(path)).jsonArray
        require(root.size in 1..MAX_DISCUSSIONS) { "Import requires 1–20 public discussions." }
        return root.map { value ->
            val record = value.jsonObject
            require(record.keys.all { it in fields }) { "Unsupported discussion field." }
            fun field(name: String): String {
                val value = record.getValue(name).jsonPrimitive
                require(value.isString) { "Discussion fields must be strings." }
                return value.content
            }
            PublicDiscussion(field("url"), field("externalId"), field("author"),
                record["publishedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
                field("title"), field("text"))
        }
    }

    fun output(path: Path, text: String) {
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permissions)
            .use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
    }
}

internal fun importResearchArgument(args: Arguments, agent: sotaos.agent.research.ResearchAgent,
    access: sotaos.agent.research.ResearchAccess): String {
    val record = Json.parseToJsonElement(ResearchFiles.text(Path.of(args.researchRequired("file")))).jsonObject
    require(record.keys.all { it in setOf("sourceId", "kind", "text", "quote", "counters") })
    fun field(name: String): String {
        val value = record.getValue(name).jsonPrimitive
        require(value.isString) { "Argument fields must be strings." }
        return value.content
    }
    return agent.addArgument(access, field("sourceId"), sotaos.agent.research.ArgumentKind.valueOf(field("kind")),
        field("text"), field("quote"), record["counters"]?.jsonPrimitive?.contentOrNull)
}
