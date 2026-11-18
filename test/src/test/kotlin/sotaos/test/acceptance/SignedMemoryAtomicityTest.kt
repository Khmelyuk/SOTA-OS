package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SignedMemoryAtomicityTest : FunSpec({
    test("Experience entity event journal and receipt failures roll back and can be retried after reopen") {
        for (table in listOf("experience", "event", "sync_journal", "verified_sync_record")) {
            SignedActionFixture().use { f ->
                f.key()
                val events = f.sourceEvents()
                val before = f.journal.records()
                f.failMemoryWrite(table, "INSERT")
                shouldThrow<Exception> { f.memory().createExperience(f.invocation, events, "Failed", "Conditions") }
                f.count("experience") shouldBe 0L
                f.journal.records() shouldBe before
                for (name in listOf("event", "sync_journal", "verified_sync_record")) f.count(name) shouldBe 2L
                f.driver.execute(null, "DROP TRIGGER memory_failure", 0)
                f.reopen()
                f.memory().createExperience(f.invocation, events, "Retried", "Conditions")
                f.count("experience") shouldBe 1L
            }
        }
    }

    test("Knowledge proposal and lifecycle updates roll back with their signed journal facts") {
        for (operation in listOf("propose", "validate", "deprecate")) {
            for (table in listOf("knowledge", "event", "sync_journal", "verified_sync_record")) {
                SignedActionFixture().use { f ->
                    f.key()
                    val runtime = f.memory()
                    val experience = f.experience(runtime)
                    val candidate = if (operation == "propose") null else
                        runtime.proposeKnowledgeCandidate(f.invocation, experience, "Statement")
                    val before = f.journal.records()
                    f.failMemoryWrite(table, if (table == "knowledge" && candidate != null) "UPDATE" else "INSERT")
                    shouldThrow<Exception> {
                        when (operation) {
                            "propose" -> runtime.proposeKnowledgeCandidate(f.invocation, experience, "Statement")
                            "validate" -> runtime.validateKnowledge(f.invocation, requireNotNull(candidate), f.actor)
                            else -> runtime.deprecate(f.invocation, requireNotNull(candidate), "Reason")
                        }
                    }
                    f.journal.records() shouldBe before
                    f.count("knowledge") shouldBe if (candidate == null) 0L else 1L
                    candidate?.let { f.repos.knowledge.findById(it.id) shouldBe it }
                    for (name in listOf("event", "sync_journal", "verified_sync_record")) {
                        f.count(name) shouldBe before.size.toLong()
                    }
                }
            }
        }
    }
})

private fun SignedActionFixture.failMemoryWrite(table: String, operation: String) {
    driver.execute(null, "CREATE TRIGGER memory_failure BEFORE $operation ON $table " +
        "BEGIN SELECT RAISE(ABORT, 'injected memory failure'); END", 0)
}
