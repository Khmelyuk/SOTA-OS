package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.exit.P10SigningKeys
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.domain.shared.*
import sotaos.security.*
import sotaos.test.sync.generateTestKey
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class P10SigningKeysTest : FunSpec({
    test("Ed25519 keystore is bound to independently provisioned public key") {
        withSigningStore { path, password, publicKey ->
            val signer = P10SigningKeys.load(path, password, "exit", publicKey)
            val hash = "a".repeat(64)
            signer.verify(hash, signer.sign(hash)) shouldBe true
            shouldThrow<IllegalArgumentException> {
                P10SigningKeys.load(path, password, "exit", Ed25519EventSigner.generate().publicKeyEncoded())
            }
            shouldThrow<IllegalArgumentException> { P10SigningKeys.load(path, password, "missing", publicKey) }
            shouldThrow<Exception> { P10SigningKeys.load(path, "wrong-password".toCharArray(), "exit", publicKey) }
        }
    }
    test("TLS EC key cannot be used as an event signing key") {
        val directory = Files.createTempDirectory("p10-ec-")
        val path = directory.resolve("ec.p12")
        val password = "fixture-password".toCharArray()
        try {
            generateTestKey(path, password)
            shouldThrow<IllegalArgumentException> {
                P10SigningKeys.load(path, password, "sync-test", Ed25519EventSigner.generate().publicKeyEncoded())
            }
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
    test("file signer resumes signed exit after database reopen and rejects revoked actor key") {
        withSigningStore { path, password, publicKey ->
            ExitFixture().use { f ->
                f.repos.actorSigningKeys.save(
                    ActorSigningKeyRecord(f.invocation.actor, "exit-key", publicKey), f.now)
                fun runtime(): SignedP10Runtime {
                    val signer = P10SigningKeys.load(path, password, "exit", publicKey)
                    return SignedP10Runtime(f.store, LocalSyncIdentity(SotaId("source"), setOf(f.invocation.actor)),
                        Context("governance"), Clock { f.now },
                        IdGenerator { UUID.randomUUID().toString() }, { signer })
                }
                val id = runtime().exit.service.requestExit(f.invocation, f.core).id
                f.reopen()
                runtime().exit.service.revokeActiveDelegations(f.invocation, id).stage shouldBe
                    ExitStage.DELEGATIONS_REVOKED
                f.repos.actorSigningKeys.revoke(f.invocation.actor, "exit-key", f.now)
                shouldThrow<IllegalArgumentException> { runtime().exit.service.closeRelations(f.invocation, id) }
                f.exits.find(id)?.stage shouldBe ExitStage.DELEGATIONS_REVOKED
            }
        }
    }
})

private fun withSigningStore(block: (Path, CharArray, String) -> Unit) {
    val directory = Files.createTempDirectory("p10-signing-")
    val path = directory.resolve("exit.p12")
    val password = "fixture-password".toCharArray()
    try {
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "exit", "-keyalg", "Ed25519", "-validity", "1", "-dname", "CN=fixture",
            "-storetype", "PKCS12", "-keystore", path.toString(), "-storepass", String(password), "-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            check(process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0)
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(path).use { store.load(it, password) }
        val publicKey = Base64.getEncoder().encodeToString(store.getCertificate("exit").publicKey.encoded)
        block(path, password, publicKey)
    } finally {
        password.fill('\u0000')
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
    }
}

private const val KEYTOOL_SECONDS = 30L
