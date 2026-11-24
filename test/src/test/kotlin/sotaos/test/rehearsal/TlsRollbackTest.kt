package sotaos.test.rehearsal

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.util.Comparator

class TlsRollbackTest : FunSpec({
    test("real CLI TLS denial preserves history and previous bundle restores exchange after restart") {
        val directory = rollbackDirectory()
        try { runTlsRollback(directory) } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
})
