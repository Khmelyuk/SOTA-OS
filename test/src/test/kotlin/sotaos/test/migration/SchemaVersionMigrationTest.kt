package sotaos.test.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.cli.main
import sotaos.persistence.UnsupportedSchemaVersionException
import java.nio.file.Files

class SchemaVersionMigrationTest : FunSpec({
    for (schema in HistoricalSchema.entries) {
        test("${schema.directory} rejects unknown versions unchanged then upgrades unversioned history") {
            HistoricalFixture(schema).use { fixture ->
                val original = fixture.sql { it.capture() }
                for (version in listOf(-1L, 2L, Int.MAX_VALUE.toLong())) {
                    fixture.execute("PRAGMA user_version = $version")
                    val bytes = Files.readAllBytes(fixture.path)
                    shouldThrow<UnsupportedSchemaVersionException> { fixture.open() }.version shouldBe version
                    shouldThrow<UnsupportedSchemaVersionException> {
                        main(arrayOf("init", "--db", fixture.path.toString()))
                    }.version shouldBe version
                    Files.readAllBytes(fixture.path).toList() shouldBe bytes.toList()
                    fixture.sql { it.capture() } shouldBe original
                }
                fixture.execute("PRAGMA user_version = 0")
                main(arrayOf("init", "--db", fixture.path.toString()))
                fixture.open()
                repeat(3) {
                    fixture.reopen()
                    fixture.sql { it.query("PRAGMA user_version") } shouldBe listOf(listOf("1"))
                    fixture.sql { connection ->
                        original.forEach { (table, image) -> connection.rows(table, image.columns) shouldBe image.rows }
                    }
                }
            }
        }
    }
})
