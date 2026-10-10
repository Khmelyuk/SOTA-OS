package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.services.AuthenticationDeniedException
import java.sql.DriverManager

class CredentialMigrationTest : FunSpec({
    test("legacy verifiers bindings and lockout survive additive migration and repeated reopen") {
        CredentialFixture().use { f ->
            val original = f.credential()
            // Reproduce the pre-lifecycle table exactly, retaining its verifier and foreign key.
            DriverManager.getConnection("jdbc:sqlite:${f.path}").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("DROP TABLE local_credential_audit")
                    sql.execute("ALTER TABLE local_credential RENAME TO previous_credential")
                    sql.execute("""CREATE TABLE local_credential (
                        unit_id TEXT NOT NULL, provider_id TEXT NOT NULL DEFAULT 'local-passphrase',
                        login_handle TEXT NOT NULL, salt_base64 TEXT NOT NULL, hash_base64 TEXT NOT NULL,
                        iterations INTEGER NOT NULL, failed_attempts INTEGER NOT NULL DEFAULT 0,
                        locked_until TEXT, created_at TEXT NOT NULL, PRIMARY KEY(unit_id, login_handle),
                        FOREIGN KEY(unit_id, provider_id, login_handle)
                            REFERENCES authentication_binding(unit_id, provider_id, provider_subject))""")
                    sql.execute("""INSERT INTO local_credential SELECT unit_id, provider_id, login_handle,
                        salt_base64, hash_base64, iterations, 5, '2026-10-09T00:01:00Z', created_at
                        FROM previous_credential""")
                    sql.execute("DROP TABLE previous_credential")
                }
            }
            repeat(2) { f.reopen() }
            f.credential().saltBase64 shouldBe original.saltBase64
            f.credential().hashBase64 shouldBe original.hashBase64
            f.credential().revision shouldBe 1L
            f.credential().failedAttempts shouldBe 5
            f.history().map { it.operation } shouldBe listOf("LEGACY_BASELINE")
            f.history().single().actor_person_id shouldBe null
            shouldThrow<AuthenticationDeniedException> { f.login() }
            f.now = f.now.plusSeconds(61)
            f.login().person shouldBe f.person
            val runtime = f.runtime
            runtime.rotate(runtime.begin(f.handle), f.oldPassword.toCharArray(), f.newPassword.toCharArray())
            f.reopen()
            f.login(f.newPassword).person shouldBe f.person
            f.history().map { it.operation } shouldBe listOf("LEGACY_BASELINE", "ROTATE")
        }
    }
})
