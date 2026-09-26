package sotaos.persistence

import sotaos.domain.rights.Consent as DomainConsent
import sotaos.domain.agency.Action as DomainAction
import sotaos.domain.agency.Decision as DomainDecision
import sotaos.domain.agency.Mission as DomainMission
import sotaos.domain.agency.Result as DomainResult
import sotaos.domain.collective.Core as DomainCore
import sotaos.domain.collective.Membership as DomainMembership
import sotaos.domain.identity.Identity as DomainIdentity
import sotaos.domain.identity.Person as DomainPerson
import sotaos.domain.memory.Event as DomainEvent
import sotaos.domain.memory.Experience as DomainExperience
import sotaos.domain.memory.Knowledge as DomainKnowledge
import sotaos.domain.relation.Authority as DomainAuthority
import app.cash.sqldelight.db.SqlDriver
import sotaos.application.ports.*
import sotaos.domain.agency.*
import sotaos.domain.collective.*
import sotaos.domain.identity.*
import sotaos.domain.memory.*
import sotaos.domain.relation.*
import sotaos.domain.rights.*
import sotaos.domain.shared.*
import sotaos.persistence.db.SotaOsDatabase
import java.security.MessageDigest
import java.time.Instant

/** Creates a local SQLite-backed database. Call [close] when the node shuts down. */
class SqlDelightStore(private val driver: SqlDriver) : AutoCloseable {
    val database: SotaOsDatabase = SotaOsDatabase(driver)

    init {
        createSyncSchemaIfMissing(driver)
        createPeerTrustSchemaIfMissing(driver)
        // Add the structured affected-person set to older decision tables.
        runCatching {
            driver.execute(null, "ALTER TABLE decision ADD COLUMN affected_persons_json TEXT NOT NULL DEFAULT '[]'", 0)
        }.onFailure { failure ->
            if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
        }
        runCatching {
            driver.execute(null,
                "ALTER TABLE decision ADD COLUMN purpose TEXT NOT NULL DEFAULT 'Execute mission action'", 0)
        }.onFailure { failure ->
            if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
        }
        // Additive compatibility migration for databases created before Rights/Consent were persisted.
        driver.execute(null, """CREATE TABLE IF NOT EXISTS right_record (
            right_id TEXT NOT NULL PRIMARY KEY, subject_kind TEXT NOT NULL, subject_id TEXT NOT NULL,
            right_type TEXT NOT NULL, scope_json TEXT NOT NULL, source TEXT NOT NULL,
            status TEXT NOT NULL, constraints_json TEXT NOT NULL)""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS consent (
            consent_id TEXT NOT NULL PRIMARY KEY, subject_person_id TEXT NOT NULL,
            recipient_kind TEXT NOT NULL, recipient_id TEXT NOT NULL, purpose TEXT NOT NULL,
            context_json TEXT NOT NULL, scope_json TEXT NOT NULL, notice TEXT NOT NULL,
            granted_at TEXT NOT NULL, valid_until TEXT, revoked_at TEXT,
            FOREIGN KEY (subject_person_id) REFERENCES person(person_id))""", 0)
        runCatching {
            driver.execute(null, "ALTER TABLE consent ADD COLUMN affirmation TEXT NOT NULL DEFAULT 'legacy-record'", 0)
        }.onFailure { failure ->
            if (!failure.message.orEmpty().contains("duplicate column", ignoreCase = true)) throw failure
        }
        driver.execute(null, """CREATE TRIGGER IF NOT EXISTS consent_no_unrevoke
            BEFORE UPDATE OF revoked_at ON consent
            WHEN OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS NULL
            BEGIN SELECT RAISE(ABORT, 'consent revocation is irreversible'); END""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS authentication_binding (
            unit_id TEXT NOT NULL, provider_id TEXT NOT NULL, provider_subject TEXT NOT NULL,
            person_id TEXT NOT NULL, linked_at TEXT NOT NULL,
            PRIMARY KEY(unit_id, provider_id, provider_subject),
            FOREIGN KEY(person_id) REFERENCES person(person_id))""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS local_credential (
            unit_id TEXT NOT NULL, provider_id TEXT NOT NULL DEFAULT 'local-passphrase',
            login_handle TEXT NOT NULL, salt_base64 TEXT NOT NULL, hash_base64 TEXT NOT NULL,
            iterations INTEGER NOT NULL, failed_attempts INTEGER NOT NULL DEFAULT 0,
            locked_until TEXT, created_at TEXT NOT NULL,
            PRIMARY KEY(unit_id, login_handle),
            FOREIGN KEY(unit_id, provider_id, login_handle)
                REFERENCES authentication_binding(unit_id, provider_id, provider_subject))""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS actor_signing_key (
            actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL, key_id TEXT NOT NULL,
            public_key_base64 TEXT NOT NULL, PRIMARY KEY (actor_kind, actor_id))""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS actor_signing_key_audit (
            audit_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            actor_kind TEXT NOT NULL, actor_id TEXT NOT NULL, key_id TEXT NOT NULL,
            operation TEXT NOT NULL, public_key_base64 TEXT, occurred_at TEXT NOT NULL)""", 0)
        driver.execute(null, """CREATE TRIGGER IF NOT EXISTS actor_signing_key_audit_no_update
            BEFORE UPDATE ON actor_signing_key_audit
            BEGIN SELECT RAISE(ABORT, 'actor signing key audit is append-only'); END""", 0)
        driver.execute(null, """CREATE TRIGGER IF NOT EXISTS actor_signing_key_audit_no_delete
            BEFORE DELETE ON actor_signing_key_audit
            BEGIN SELECT RAISE(ABORT, 'actor signing key audit is append-only'); END""", 0)
    }

    /** Close the driver when the local node shuts down. */
    override fun close() = driver.close()
}

/** One adapter bundle exposes implementations for every MVP repository port. */
class SqlDelightRepositories(database: SotaOsDatabase) {
    val persons: PersonRepository = SqlDelightPersonRepository(database)
    val identities: IdentityRepository = SqlDelightIdentityRepository(database)
    val actorSigningKeys: ActorSigningKeyRepository = SqlDelightActorSigningKeyRepository(database)
    val cores: CoreRepository = SqlDelightCoreRepository(database)
    val memberships: MembershipRepository = SqlDelightMembershipRepository(database)
    val authorities: AuthorityRepository = SqlDelightAuthorityRepository(database)
    val missions: MissionRepository = SqlDelightMissionRepository(database)
    val decisions: DecisionRepository = SqlDelightDecisionRepository(database)
    val actions: ActionRepository = SqlDelightActionRepository(database)
    val results: ResultRepository = SqlDelightResultRepository(database)
    val events: EventStore = SqlDelightEventStore(database)
    val experiences: ExperienceRepository = SqlDelightExperienceRepository(database)
    val knowledge: KnowledgeRepository = SqlDelightKnowledgeRepository(database)
    val rights: sotaos.domain.rights.RightsRepository = SqlDelightRightsRepository(database)
    val consents: sotaos.domain.rights.ConsentRepository = SqlDelightConsentRepository(database)
    val authentication: sotaos.application.ports.AuthenticationRepository = SqlDelightAuthenticationRepository(database)
}

class SqlDelightRightsRepository(private val db: SotaOsDatabase) : sotaos.domain.rights.RightsRepository {
    override fun save(right: Right): Right {
        val (kind, subjectId) = ValueJsonMapping.subject(right.subject)
        db.schemaQueries.insertRight(right.id.value, kind, subjectId, right.type,
            JsonMapping.scope(right.scope), right.source, right.status.name,
                ValueJsonMapping.strings(right.constraints))
        return right
    }

    override fun findById(id: RightId): Right? = db.schemaQueries.selectRightById(id.value)
        .executeAsOneOrNull()?.let { row ->
            Right(RightId(row.right_id), ValueJsonMapping.subject(row.subject_kind, row.subject_id), row.right_type,
                JsonMapping.scope(row.scope_json), row.source, RightStatus.valueOf(row.status),
                ValueJsonMapping.strings(row.constraints_json).toSet())
        }

    override fun findBySubject(subject: SubjectRef): List<Right> {
        val (kind, subjectId) = ValueJsonMapping.subject(subject)
        return db.schemaQueries.selectRightsBySubject(kind, subjectId).executeAsList().map { row ->
            Right(RightId(row.right_id), ValueJsonMapping.subject(row.subject_kind, row.subject_id), row.right_type,
                JsonMapping.scope(row.scope_json), row.source, RightStatus.valueOf(row.status),
                ValueJsonMapping.strings(row.constraints_json).toSet())
        }
    }
}

class SqlDelightConsentRepository(private val db: SotaOsDatabase) : sotaos.domain.rights.ConsentRepository {
    override fun save(consent: DomainConsent): DomainConsent {
        val (kind, recipientId) = ValueJsonMapping.subject(consent.recipient)
        if (db.schemaQueries.selectConsentById(consent.id.value).executeAsOneOrNull() == null) {
            db.schemaQueries.insertConsent(consent.id.value, consent.subject.value, kind, recipientId,
                consent.purpose, JsonMapping.context(consent.context), JsonMapping.scope(consent.scope), consent.notice,
                consent.affirmation, consent.grantedAt.toString(), consent.validUntil?.toString(),
                    consent.revokedAt?.toString())
        } else {
            db.schemaQueries.updateConsent(consent.revokedAt?.toString(), consent.id.value)
        }
        return consent
    }

    override fun findById(id: ConsentId): DomainConsent? = db.schemaQueries.selectConsentById(id.value)
        .executeAsOneOrNull()?.toDomain()

    override fun findBySubject(subject: PersonId): List<DomainConsent> = db.schemaQueries
        .selectConsentsBySubject(subject.value).executeAsList().map { it.toDomain() }
}

private fun sotaos.persistence.Consent.toDomain(): DomainConsent = DomainConsent(
    id = ConsentId(consent_id), subject = PersonId(subject_person_id),
    recipient = ValueJsonMapping.subject(recipient_kind, recipient_id), purpose = purpose,
    context = JsonMapping.context(context_json), scope = JsonMapping.scope(scope_json), notice = notice,
    affirmation = affirmation,
    grantedAt = Instant.parse(granted_at), validUntil = valid_until?.let(Instant::parse),
    revokedAt = revoked_at?.let(Instant::parse)
)

class SqlDelightPersonRepository(private val db: SotaOsDatabase) : PersonRepository {
    override fun save(person: DomainPerson): DomainPerson {
        db.schemaQueries.insertPerson(person.id.value, person.createdAt.toString())
        return person
    }

    override fun findById(id: PersonId): DomainPerson? = db.schemaQueries.selectPersonById(id.value)
        .executeAsOneOrNull()?.let { row ->
            DomainPerson(id = PersonId(row.person_id), identities = db.schemaQueries
                .selectIdentitiesByPerson(row.person_id).executeAsList().map { IdentityId(it.identity_id) },
                createdAt = Instant.parse(row.created_at))
        }
}

class SqlDelightIdentityRepository(private val db: SotaOsDatabase) : IdentityRepository {
    override fun save(identity: DomainIdentity): DomainIdentity {
        db.schemaQueries.insertIdentity(identity.id.value, identity.ownerPerson.value,
            identity.localHandle, ValueJsonMapping.strings(identity.externalRefs))
        return identity
    }

    override fun findByPerson(person: PersonId): List<DomainIdentity> = db.schemaQueries
        .selectIdentitiesByPerson(person.value).executeAsList().map {
            DomainIdentity(IdentityId(it.identity_id), PersonId(it.person_id), it.local_handle,
                ValueJsonMapping.strings(it.external_refs_json))
        }
}

class SqlDelightCoreRepository(private val db: SotaOsDatabase) : CoreRepository {
    override fun save(core: DomainCore): DomainCore {
        db.schemaQueries.insertCore(core.id.value, core.name, core.createdAt.toString(), core.state.name)
        return core
    }

    override fun findById(id: CoreId): DomainCore? = db.schemaQueries.selectCoreById(id.value)
        .executeAsOneOrNull()?.let { DomainCore(CoreId(it.core_id), it.name, Instant.parse(it.created_at),
            LifecycleState.valueOf(it.state)) }
}

class SqlDelightMembershipRepository(private val db: SotaOsDatabase) : MembershipRepository {
    override fun save(membership: DomainMembership): DomainMembership {
        val (kind, collectiveId) = ValueJsonMapping.subject(membership.collective)
        val key = listOf(membership.subject.value, kind, collectiveId,
            membership.role?.value.orEmpty(), membership.start.toString()).joinToString("\u0000")
        val membershipId = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val existing = db.schemaQueries.selectMembershipById(membershipId).executeAsOneOrNull()
        if (existing == null) {
            db.schemaQueries.insertMembership(membershipId, membership.subject.value, kind, collectiveId,
                membership.role?.value, membership.start.toString(), membership.end?.toString(), membership.state.name)
        } else {
            db.schemaQueries.updateMembership(membership.subject.value, kind, collectiveId,
                membership.role?.value, membership.start.toString(), membership.end?.toString(),
                membership.state.name, membershipId)
        }
        return membership
    }

    override fun findActiveFor(subject: PersonId, collective: SubjectRef): DomainMembership? {
        val (kind, collectiveId) = ValueJsonMapping.subject(collective)
        return db.schemaQueries.selectActiveMembership(subject.value, kind, collectiveId)
            .executeAsOneOrNull()?.let { row ->
                DomainMembership(PersonId(row.person_id), ValueJsonMapping.subject(row.collective_kind,
                    row.collective_id),
                    row.role_id?.let(::RoleId), Instant.parse(row.start_at), row.end_at?.let(Instant::parse),
                    LifecycleState.valueOf(row.state))
            }
    }
}

class SqlDelightAuthorityRepository(private val db: SotaOsDatabase) : AuthorityRepository {
    override fun save(authority: DomainAuthority): DomainAuthority {
        val (issuerKind, issuerId) = ValueJsonMapping.subject(authority.issuer)
        val (subjectKind, subjectId) = ValueJsonMapping.subject(authority.subject)
        val (accountableKind, accountableId) = ValueJsonMapping.subject(authority.accountabilityTarget)
        if (db.schemaQueries.selectAuthorityById(authority.id.value).executeAsOneOrNull() == null) {
            db.schemaQueries.insertAuthority(authority.id.value, issuerKind, issuerId, subjectKind, subjectId,
                JsonMapping.scope(authority.scope), JsonMapping.context(authority.context),
                AuthorityBasisJsonMapping.basis(authority.basis), authority.validity.from.toString(),
                authority.validity.until?.toString(), accountableKind, accountableId, authority.state.name)
        } else {
            db.schemaQueries.updateAuthority(issuerKind, issuerId, subjectKind, subjectId,
                JsonMapping.scope(authority.scope), JsonMapping.context(authority.context),
                AuthorityBasisJsonMapping.basis(authority.basis), authority.validity.from.toString(),
                authority.validity.until?.toString(), accountableKind, accountableId,
                authority.state.name, authority.id.value)
        }
        return authority
    }

    override fun findById(id: AuthorityId): DomainAuthority? = db.schemaQueries.selectAuthorityById(id.value)
        .executeAsOneOrNull()?.toDomain()?.let(::effectiveState)

    override fun findActiveFor(subject: SubjectRef, context: Context): List<DomainAuthority> {
        val (kind, id) = ValueJsonMapping.subject(subject)
        return db.schemaQueries.selectAuthoritiesBySubject(kind, id).executeAsList()
            .map { effectiveState(it.toDomain()) }
            .filter { it.state == LifecycleState.ACTIVE && it.context == context }
    }

    private fun effectiveState(authority: DomainAuthority): DomainAuthority =
        if (db.syncQueries.hasConflict("AUTHORITY", authority.id.value).executeAsOne() > 0) {
            authority.copy(state = LifecycleState.CONTESTED)
        } else {
            authority
        }
}

private fun sotaos.persistence.Authority.toDomain(): DomainAuthority = DomainAuthority(
    id = AuthorityId(authority_id), issuer = ValueJsonMapping.subject(issuer_kind, issuer_id),
    subject = ValueJsonMapping.subject(subject_kind, subject_id), scope = JsonMapping.scope(scope_json),
    context = JsonMapping.context(context_json), basis = AuthorityBasisJsonMapping.basis(basis_json),
    validity = Validity(Instant.parse(valid_from), valid_until?.let(Instant::parse)),
    accountabilityTarget = ValueJsonMapping.subject(accountable_kind, accountable_id),
    state = LifecycleState.valueOf(state)
)

class SqlDelightMissionRepository(private val db: SotaOsDatabase) : MissionRepository {
    override fun save(mission: DomainMission): DomainMission {
        val (kind, id) = ValueJsonMapping.subject(mission.owner)
        db.schemaQueries.insertMission(mission.id.value, kind, id, mission.intent,
            ValueJsonMapping.strings(mission.objectives),
                ValueJsonMapping.strings(mission.participants.map { it.value }),
            mission.mode.name, mission.state.name)
        return mission
    }

    override fun findById(id: MissionId): DomainMission? = db.schemaQueries.selectMissionById(id.value)
        .executeAsOneOrNull()?.let {
            DomainMission(MissionId(it.mission_id), ValueJsonMapping.subject(it.owner_kind, it.owner_id), it.intent,
                ValueJsonMapping.strings(it.objectives_json),
                    ValueJsonMapping.strings(it.participants_json).map(::PersonId),
                Mode.valueOf(it.mode), LifecycleState.valueOf(it.state))
        }
}

class SqlDelightDecisionRepository(private val db: SotaOsDatabase) : DecisionRepository {
    override fun save(decision: DomainDecision): DomainDecision {
        val (kind, id) = ValueJsonMapping.subject(decision.subject)
        db.schemaQueries.insertDecision(decision.id.value, kind, id, decision.intentRef,
            ValueJsonMapping.strings(decision.optionsConsidered), decision.chosenOption,
            decision.authorityRef.value, decision.timestamp.toString(), decision.purpose,
            ValueJsonMapping.strings(decision.affectedPersons.map { it.value }))
        return decision
    }

    override fun findById(id: DecisionId): DomainDecision? = db.schemaQueries.selectDecisionById(id.value)
        .executeAsOneOrNull()?.let { row ->
            DomainDecision(
                id = DecisionId(row.decision_id),
                subject = ValueJsonMapping.subject(row.subject_kind, row.subject_id),
                intentRef = row.intent_ref,
                optionsConsidered = ValueJsonMapping.strings(row.options_json),
                chosenOption = row.chosen_option,
                authorityRef = AuthorityId(row.authority_ref),
                timestamp = Instant.parse(row.timestamp),
                purpose = row.purpose,
                affectedPersons = ValueJsonMapping.strings(row.affected_persons_json).map(::PersonId)
            )
        }
}

class SqlDelightActionRepository(private val db: SotaOsDatabase) : ActionRepository {
    override fun save(action: DomainAction): DomainAction {
        val (kind, id) = ValueJsonMapping.subject(action.actor)
        db.schemaQueries.insertAction(action.id.value, kind, id, action.authority.value,
            action.decision?.value, JsonMapping.context(action.context), action.timestamp.toString(),
            action.mode.name, action.target)
        return action
    }
}

class SqlDelightResultRepository(private val db: SotaOsDatabase) : ResultRepository {
    override fun save(result: DomainResult): DomainResult {
        db.schemaQueries.insertResult(result.id.value, result.action.value, result.description,
            result.outcome.name, result.timestamp.toString())
        return result
    }
}

class SqlDelightActorSigningKeyRepository(private val db: SotaOsDatabase) : ActorSigningKeyRepository {
    override fun save(record: ActorSigningKeyRecord, occurredAt: Instant): ActorSigningKeyRecord {
        val (kind, id) = ValueJsonMapping.subject(record.actor)
        db.transaction {
            val operation = if (db.schemaQueries.selectActorSigningKey(kind, id).executeAsOneOrNull() == null) {
                ActorSigningKeyOperation.ENROLL
            } else {
                ActorSigningKeyOperation.ROTATE
            }
            db.schemaQueries.insertActorSigningKey(kind, id, record.keyId, record.publicKeyBase64)
            db.schemaQueries.insertActorSigningKeyAudit(kind, id, record.keyId, operation.name,
                record.publicKeyBase64, occurredAt.toString())
        }
        return record
    }

    override fun findByActor(actor: SubjectRef): ActorSigningKeyRecord? {
        val (kind, id) = ValueJsonMapping.subject(actor)
        return db.schemaQueries.selectActorSigningKey(kind, id).executeAsOneOrNull()?.toDomain()
    }

    override fun findAll(): List<ActorSigningKeyRecord> =
        db.schemaQueries.selectAllActorSigningKeys().executeAsList().map { it.toDomain() }

    override fun revoke(actor: SubjectRef, keyId: String, occurredAt: Instant) {
        val (kind, id) = ValueJsonMapping.subject(actor)
        db.transaction {
            val current = db.schemaQueries.selectActorSigningKey(kind, id).executeAsOneOrNull()
            require(current?.key_id == keyId) { "Key $keyId is not the active key for actor $id." }
            db.schemaQueries.deleteActorSigningKey(kind, id, keyId)
            db.schemaQueries.insertActorSigningKeyAudit(kind, id, keyId, ActorSigningKeyOperation.REVOKE.name,
                current.public_key_base64, occurredAt.toString())
        }
    }

    override fun auditByActor(actor: SubjectRef): List<ActorSigningKeyAuditRecord> {
        val (kind, id) = ValueJsonMapping.subject(actor)
        return db.schemaQueries.selectActorSigningKeyAudit(kind, id).executeAsList().map {
            ActorSigningKeyAuditRecord(ValueJsonMapping.subject(it.actor_kind, it.actor_id), it.key_id,
                ActorSigningKeyOperation.valueOf(it.operation), it.public_key_base64,
                Instant.parse(it.occurred_at))
        }
    }
}

private fun sotaos.persistence.Actor_signing_key.toDomain(): ActorSigningKeyRecord = ActorSigningKeyRecord(
    actor = ValueJsonMapping.subject(actor_kind, actor_id),
    keyId = key_id,
    publicKeyBase64 = public_key_base64
)

class SqlDelightEventStore(private val db: SotaOsDatabase) : EventStore {
    override fun append(event: DomainEvent): DomainEvent {
        require(db.schemaQueries.selectEventById(event.id.value).executeAsOneOrNull() == null) {
            "Event ${event.id.value} already recorded — append-only violation."
        }
        val (kind, actorId) = ValueJsonMapping.subject(event.actor)
        db.schemaQueries.insertEvent(event.id.value, event.type, kind, actorId, event.timestamp.toString(),
            JsonMapping.context(event.context), event.authorityRef, event.decisionRef,
            PayloadJsonMapping.payload(event.payload), event.resultRef, JsonMapping.provenance(event.provenance),
            event.contentHash, event.signature, event.correctsEventId?.value)
        return event
    }

    override fun findById(id: EventId): DomainEvent? = db.schemaQueries.selectEventById(id.value)
        .executeAsOneOrNull()?.toDomain()

    override fun findByActor(actor: SubjectRef): List<DomainEvent> {
        val (kind, id) = ValueJsonMapping.subject(actor)
        return db.schemaQueries.selectEventsByActor(kind, id).executeAsList().map { it.toDomain() }
    }
}

private fun sotaos.persistence.Event.toDomain(): DomainEvent = DomainEvent(
    id = EventId(event_id), type = type,
    actor = ValueJsonMapping.subject(actor_kind, actor_id), timestamp = Instant.parse(timestamp),
    context = JsonMapping.context(context_json), authorityRef = authority_ref, decisionRef = decision_ref,
    payload = PayloadJsonMapping.payload(payload_json), resultRef = result_ref,
    provenance = JsonMapping.provenance(provenance_json), contentHash = content_hash,
    signature = signature, correctsEventId = corrects_event_id?.let(::EventId)
)

class SqlDelightExperienceRepository(private val db: SotaOsDatabase) : ExperienceRepository {
    override fun save(experience: DomainExperience): DomainExperience {
        db.schemaQueries.insertExperience(experience.id.value,
            ValueJsonMapping.strings(experience.derivedFromEvents.map { it.value }),
                JsonMapping.context(experience.context),
            experience.whatWasTried, experience.outcome, experience.conclusion,
            experience.applicabilityConditions, experience.confidence.name,
                JsonMapping.provenance(experience.provenance))
        return experience
    }
}

class SqlDelightKnowledgeRepository(private val db: SotaOsDatabase) : KnowledgeRepository {
    override fun save(knowledge: DomainKnowledge): DomainKnowledge {
        val derived = ValueJsonMapping.strings(knowledge.derivedFromExperience.map { it.value })
        val evidence = ValueJsonMapping.strings(knowledge.evidence.map { it.value })
        val context = JsonMapping.context(knowledge.context)
        val provenance = JsonMapping.provenance(knowledge.provenance)
        if (db.schemaQueries.selectKnowledgeById(knowledge.id.value).executeAsOneOrNull() == null) {
            db.schemaQueries.insertKnowledge(knowledge.id.value, knowledge.statement, derived, evidence, context,
                knowledge.status.name, knowledge.version.toLong(), knowledge.supersedes?.value, provenance, null, null)
        } else {
            db.schemaQueries.updateKnowledge(knowledge.statement, derived, evidence, context, knowledge.status.name,
                knowledge.version.toLong(), knowledge.supersedes?.value, provenance, null, null, knowledge.id.value)
        }
        return knowledge
    }

    override fun findById(id: KnowledgeId): DomainKnowledge? = db.schemaQueries.selectKnowledgeById(id.value)
        .executeAsOneOrNull()?.let {
            DomainKnowledge(KnowledgeId(it.knowledge_id), it.statement,
                ValueJsonMapping.strings(it.derived_experience_json).map(::ExperienceId),
                ValueJsonMapping.strings(it.evidence_json).map(::EvidenceId), JsonMapping.context(it.context_json),
                KnowledgeStatus.valueOf(it.status), it.version.toInt(), it.supersedes?.let(::KnowledgeId),
                JsonMapping.provenance(it.provenance_json))
        }
}
