package sotaos.test.acceptance

import sotaos.api.collective.CoreInvitationRequest
import sotaos.api.collective.P08AdmissionRuntime
import sotaos.api.collective.RecordedInvitation
import sotaos.application.ports.*
import sotaos.domain.collective.Core
import sotaos.domain.identity.Person
import sotaos.domain.relation.Authority
import sotaos.domain.relation.AuthorityBasis
import sotaos.domain.shared.*
import sotaos.security.NoAgentActionRule
import sotaos.security.RightsConstraintDecorator
import java.util.UUID

internal class AdmissionFixture : AutoCloseable {
    val credentials = CredentialFixture()
    val repos get() = credentials.repositories
    val core = CoreId("admission-core")
    val collective = SubjectRef.Core(core)
    val invitee = PersonId("invitee")
    val context = Context("membership-governance")
    val request = CoreInvitationRequest(core, invitee, null, AuthorityId("invitation-root"), "Invite a collaborator")
    val root get() = requireNotNull(repos.authorities.findById(request.authority))

    init {
        repos.cores.save(Core(core, "Admission Core", credentials.now, LifecycleState.ACTIVE))
        repos.persons.save(Person(invitee, emptyList(), credentials.now))
        credentials.provider.enroll(credentials.unit, "invitee", invitee,
            credentials.newPassword.toCharArray(), credentials.now)
        repos.authorities.save(Authority(request.authority, collective, SubjectRef.Person(credentials.person),
            Scope(setOf("membership.invite"), setOf("core:${core.value}", "person:${invitee.value}",
                "membership-unit:${credentials.unit.value}")), context,
            AuthorityBasis(note = "Explicit trusted mandate"),
            Validity(credentials.now.minusSeconds(1), credentials.now.plusSeconds(3600)),
            collective, LifecycleState.ACTIVE))
    }

    fun runtime(unit: SotaId = credentials.unit, governance: Context = context,
        rights: RightsConstraint = RightsConstraintDecorator(listOf(NoAgentActionRule))) =
        P08AdmissionRuntime(credentials.store, unit, governance, Clock { credentials.now },
            IdGenerator { UUID.randomUUID().toString() }, rights)

    fun issue(runtime: P08AdmissionRuntime = runtime(), terms: CoreInvitationRequest = request): RecordedInvitation =
        runtime.invite(runtime.begin(credentials.handle), credentials.oldPassword.toCharArray(), terms)

    fun accept(runtime: P08AdmissionRuntime, invitation: RecordedInvitation) = runtime.accept(runtime.begin("invitee"),
        credentials.newPassword.toCharArray(), invitation, "I accept these membership terms")

    fun count(table: String): Long = credentials.driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { cursor ->
        cursor.next()
        app.cash.sqldelight.db.QueryResult.Value(requireNotNull(cursor.getLong(0)))
    }, 0).value

    override fun close() = credentials.close()
}
