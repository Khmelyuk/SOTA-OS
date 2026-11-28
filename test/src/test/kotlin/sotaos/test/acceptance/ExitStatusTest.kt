package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.api.exit.P10Runtime
import sotaos.api.exit.SignedP10Runtime
import sotaos.application.ports.*
import sotaos.domain.collective.Membership
import sotaos.domain.shared.*
import sotaos.security.Ed25519EventSigner
import sotaos.security.LocalSyncIdentity
import java.nio.file.Files
import java.util.UUID

class ExitStatusTest : FunSpec({
    test("status of an active membership with no exit creates no process archive or event") {
        ExitFixture().use { f ->
            val before = Files.readAllBytes(f.path).toList()
            repeat(2) {
                f.runtime.status(f.invocation, f.core) shouldBe ExitStatus(f.target, true, emptyList())
            }
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("all persisted stages can be inspected without advancing them including after restart") {
        ExitFixture().use { f ->
            val id = f.runtime.service.requestExit(f.invocation, f.core).id
            val stages = listOf<() -> Unit>(
                {},
                { f.runtime.service.revokeActiveDelegations(f.invocation, id) },
                { f.runtime.service.closeRelations(f.invocation, id) },
                { f.runtime.service.settleObligations(f.invocation, id) },
                { f.runtime.service.exportPortableData(f.invocation, id) },
                {
                    f.runtime.service.terminateParticipation(f.invocation, id,
                        requireNotNull(f.exits.export(id)).sha256)
                }
            )
            stages.forEachIndexed { index, advance ->
                advance()
                f.reopen()
                val before = Files.readAllBytes(f.path).toList()
                val status = f.runtime.status(f.invocation, f.core)
                status.target shouldBe f.target
                status.membershipActive shouldBe (index < ExitStage.COMPLETED.ordinal)
                status.history.single() shouldBe
                    ExitProgress(requireNotNull(f.exits.find(id)), f.exits.export(id)?.sha256)
                status.history.single().process.stage shouldBe ExitStage.entries[index]
                f.runtime.status(f.invocation, f.core) shouldBe status
                Files.readAllBytes(f.path).toList() shouldBe before
            }
        }
    }
    test("status filters both Person and Core and has no caller supplied Person selector") {
        ExitFixture().use { f ->
            val chosen = f.prepare()
            val other = f.runtime.service.requestExit(f.invocation, f.other).id
            val before = Files.readAllBytes(f.path).toList()
            f.runtime.status(f.invocation, f.core).history.map { it.process.id } shouldBe listOf(chosen)
            f.runtime.status(f.invocation, f.other).history.map { it.process.id } shouldBe listOf(other)
            val stranger = f.invocation.copy(actor = SubjectRef.Person(PersonId("different-person")))
            f.runtime.status(stranger, f.core) shouldBe
                ExitStatus(ExitTarget(PersonId("different-person"), f.core), false, emptyList())
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("inspection enforces scoped policy even for completed exits and denies non-Person actors") {
        ExitFixture().use { f ->
            val id = f.prepare()
            val document = f.runtime.service.exportPortableData(f.invocation, id)
            f.runtime.service.terminateParticipation(f.invocation, id, document.sha256)
            val before = Files.readAllBytes(f.path).toList()
            val denied = P10Runtime(f.store, Clock { f.now }, IdGenerator { error("Read must not allocate IDs") },
                RightsConstraint {
                    it.operation shouldBe "status"
                    it.scope shouldBe Scope(setOf("status"), setOf("core:${f.core.value}"))
                    throw RightsConstraintViolation("DENY", "test read policy")
                })
            shouldThrow<RightsConstraintViolation> { denied.status(f.invocation, f.core) }
            for (actor in listOf(SubjectRef.Core(f.core), SubjectRef.Sota(SotaId("unit")),
                SubjectRef.Agent(AgentId("tool")))) {
                shouldThrow<IllegalArgumentException> { f.runtime.status(f.invocation.copy(actor = actor), f.core) }
            }
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("completed history does not hide current membership or a later exit after rejoining") {
        ExitFixture().use { f ->
            val first = f.prepare()
            val document = f.runtime.service.exportPortableData(f.invocation, first)
            f.runtime.service.terminateParticipation(f.invocation, first, document.sha256)
            // Trusted membership fixture; status itself cannot admit or re-admit a Person.
            f.repos.memberships.save(Membership(f.person, SubjectRef.Core(f.core), null,
                f.now.plusSeconds(1), null, LifecycleState.ACTIVE))
            f.runtime.status(f.invocation, f.core).membershipActive shouldBe true
            val second = f.runtime.service.requestExit(f.invocation, f.core).id
            f.reopen()
            val before = Files.readAllBytes(f.path).toList()
            val status = f.runtime.status(f.invocation, f.core)
            status.membershipActive shouldBe true
            status.history.associate { it.process.id to it.process.stage } shouldBe
                mapOf(first to ExitStage.COMPLETED, second to ExitStage.REQUESTED)
            status.history.single { it.process.id == first }.archiveSha256 shouldBe document.sha256
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
    test("signed exit remains inspectable after signer retirement without new signed or unsigned facts") {
        ExitFixture().use { f ->
            val signer = Ed25519EventSigner.generate()
            f.repos.actorSigningKeys.save(ActorSigningKeyRecord(f.invocation.actor, "status-key",
                signer.publicKeyEncoded()), f.now)
            val signed = SignedP10Runtime(f.store, LocalSyncIdentity(SotaId("source"), setOf(f.invocation.actor)),
                Context("governance"), Clock { f.now }, IdGenerator { UUID.randomUUID().toString() }, { signer })
            val id = signed.exit.service.requestExit(f.invocation, f.core).id
            f.repos.actorSigningKeys.revoke(f.invocation.actor, "status-key", f.now.plusSeconds(1))
            f.reopen()
            val before = Files.readAllBytes(f.path).toList()
            f.runtime.status(f.invocation, f.core).history.single() shouldBe
                ExitProgress(requireNotNull(f.exits.find(id)), null)
            f.exits.transitions(id).size shouldBe 1
            Files.readAllBytes(f.path).toList() shouldBe before
        }
    }
})
