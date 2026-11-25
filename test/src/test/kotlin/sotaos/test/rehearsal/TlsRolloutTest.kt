package sotaos.test.rehearsal

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.util.Comparator

class TlsRolloutTest : FunSpec({
    test("overlapping trust delivers signed history through mixed certificates and rejects retired peers") {
        val directory = rolloutDirectory()
        try { runTlsRollout(directory) } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
})
