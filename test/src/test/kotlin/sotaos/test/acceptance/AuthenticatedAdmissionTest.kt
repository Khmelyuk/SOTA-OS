package sotaos.test.acceptance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.application.services.AuthenticationDeniedException
import sotaos.domain.collective.RoleId
import sotaos.domain.shared.*

class AuthenticatedAdmissionTest : FunSpec({
    test("fresh authenticated representative invites and invitee accepts after reopen with human audit") {
        AdmissionFixture().use { f ->
            var runtime = f.runtime()
            val proof = f.credentials.oldPassword.toCharArray()
            val challenge = runtime.begin(f.credentials.handle)
            val invitation = runtime.invite(challenge, proof, f.request.copy(role = RoleId("reviewer")))
            proof.all { it == '\u0000' } shouldBe true
            invitation.membership.state shouldBe LifecycleState.PROPOSED
            f.repos.memberships.findActiveFor(f.invitee, f.collective) shouldBe null
            val issued = requireNotNull(f.repos.events.findById(invitation.auditEvent))
            issued.actor shouldBe SubjectRef.Person(f.credentials.person)
            issued.authorityRef shouldBe f.request.authority.value
            issued.payload["unitId"] shouldBe f.credentials.unit.value
            issued.payload["providerId"] shouldBe "local-passphrase"
            issued.provenance.author shouldBe issued.actor
            issued.signature shouldBe null
            issued.payload.toString().contains(f.credentials.oldPassword) shouldBe false
            shouldThrow<AuthenticationDeniedException> {
                runtime.invite(challenge, f.credentials.oldPassword.toCharArray(), f.request)
            }
            f.credentials.reopen()
            runtime = f.runtime()
            val accepted = f.accept(runtime, invitation)
            accepted.state shouldBe LifecycleState.ACTIVE
            accepted.role shouldBe RoleId("reviewer")
            val audit = f.repos.events.findByActor(SubjectRef.Person(f.invitee)).single()
            audit.type shouldBe "MEMBERSHIP_ACCEPTED"
            audit.provenance.sourceEventId shouldBe invitation.auditEvent
            audit.provenance.author shouldBe SubjectRef.Person(f.invitee)
            shouldThrow<IllegalArgumentException> { f.accept(runtime, invitation) }
            f.count("membership") shouldBe 1L
            f.count("authority") shouldBe 1L
            f.count("event") shouldBe 2L
            f.count("sync_journal") shouldBe 0L
            shouldThrow<Exception> { f.credentials.driver.execute(null, "UPDATE event SET type = 'forged'", 0) }
            shouldThrow<Exception> { f.credentials.driver.execute(null, "DELETE FROM event", 0) }
        }
    }

    test("wrong proof is cleared and failed authentication counters survive without business writes") {
        AdmissionFixture().use { f ->
            val runtime = f.runtime()
            val proof = "incorrect-proof".toCharArray()
            shouldThrow<AuthenticationDeniedException> {
                runtime.invite(runtime.begin(f.credentials.handle), proof, f.request)
            }
            proof.all { it == '\u0000' } shouldBe true
            f.credentials.credential().failedAttempts shouldBe 1
            f.count("membership") shouldBe 0L
            f.count("event") shouldBe 0L
        }
    }

    test("forged expired and previous-runtime challenges cannot authorize invitation") {
        AdmissionFixture().use { f ->
            val runtime = f.runtime()
            val challenge = runtime.begin(f.credentials.handle)
            shouldThrow<AuthenticationDeniedException> {
                runtime.invite(challenge.copy(subjectHint = "invitee"),
                    f.credentials.oldPassword.toCharArray(), f.request)
            }
            val expired = runtime.begin(f.credentials.handle)
            f.credentials.now = expired.expiresAt
            shouldThrow<AuthenticationDeniedException> {
                runtime.invite(expired, f.credentials.oldPassword.toCharArray(), f.request)
            }
            val unknown = runtime.begin(f.credentials.handle)
            f.credentials.reopen()
            shouldThrow<AuthenticationDeniedException> {
                f.runtime().invite(unknown, f.credentials.oldPassword.toCharArray(), f.request)
            }
            f.count("membership") shouldBe 0L
            f.count("event") shouldBe 0L
        }
    }

    test("authenticated wrong Person and altered or unknown invitation receipts cannot accept") {
        AdmissionFixture().use { f ->
            val runtime = f.runtime()
            val invitation = f.issue(runtime)
            shouldThrow<IllegalArgumentException> {
                runtime.accept(runtime.begin(f.credentials.handle), f.credentials.oldPassword.toCharArray(),
                    invitation, "Pretend to be invited")
            }
            val variants = listOf(invitation.copy(auditEvent = EventId("unknown")),
                invitation.copy(membership = invitation.membership.copy(role = RoleId("altered"))),
                invitation.copy(membership = invitation.membership.copy(start = f.credentials.now.minusSeconds(1))),
                invitation.copy(membership = invitation.membership.copy(state = LifecycleState.ACTIVE)))
            variants.forEach { forged -> shouldThrow<IllegalArgumentException> { f.accept(runtime, forged) } }
            f.repos.memberships.findRecord(invitation.membership) shouldBe invitation.membership
            f.count("event") shouldBe 1L
        }
    }

    test("another Sota unit or configured governance context cannot reuse a mandate or invitation") {
        AdmissionFixture().use { f ->
            val invitation = f.issue()
            val other = SotaId("other-unit")
            f.credentials.provider.enroll(other, f.credentials.handle, f.credentials.person,
                f.credentials.oldPassword.toCharArray(), f.credentials.now)
            f.credentials.provider.enroll(other, "invitee", f.invitee,
                f.credentials.newPassword.toCharArray(), f.credentials.now)
            for (runtime in listOf(f.runtime(unit = other), f.runtime(governance = Context("other")))) {
                shouldThrow<IllegalArgumentException> { f.issue(runtime) }
                shouldThrow<IllegalArgumentException> { f.accept(runtime, invitation) }
            }
            f.count("membership") shouldBe 1L
            f.count("event") shouldBe 1L
        }
    }
})
