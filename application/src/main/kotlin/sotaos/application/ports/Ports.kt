package sotaos.application.ports

import sotaos.domain.identity.*
import sotaos.domain.collective.*
import sotaos.domain.relation.*
import sotaos.domain.agency.*
import sotaos.domain.memory.*
import sotaos.domain.shared.*
import java.time.Instant

/**
 * Repository ports. Domain has no knowledge of these — application
 * defines them, persistence implements them, per the layering rule in
 * ADR-007. Every "unusual" field (nullable authorityRef, etc.) mirrors
 * exactly the Domain type; ports must never widen or narrow a Domain
 * invariant.
 */

interface PersonRepository {
    fun save(person: Person): Person
    fun findById(id: PersonId): Person?
}

interface IdentityRepository {
    fun save(identity: Identity): Identity
    fun findByPerson(person: PersonId): List<Identity>
}

interface CoreRepository {
    fun save(core: Core): Core
    fun findById(id: CoreId): Core?
}

interface MembershipRepository {
    fun save(membership: Membership): Membership
    fun findActiveFor(subject: PersonId, collective: SubjectRef): Membership?
}

interface AuthorityRepository {
    fun save(authority: Authority): Authority
    fun findById(id: AuthorityId): Authority?
    fun findActiveFor(subject: SubjectRef, context: Context): List<Authority>
}

interface MissionRepository {
    fun save(mission: Mission): Mission
    fun findById(id: MissionId): Mission?
}

interface DecisionRepository {
    fun save(decision: Decision): Decision
    fun findById(id: DecisionId): Decision?
}

interface ActionRepository {
    fun save(action: Action): Action
}

interface ResultRepository {
    fun save(result: Result): Result
}

/**
 * EventStore is intentionally append-only at the port level: there is
 * no `update`/`delete` method. This is the single write path for the
 * `event` table (ADR-003).
 */
interface EventStore {
    fun append(event: Event): Event
    fun findById(id: EventId): Event?
    fun findByActor(actor: SubjectRef): List<Event>
}

interface ExperienceRepository {
    fun save(experience: Experience): Experience
}

typealias RightsRepository = sotaos.domain.rights.RightsRepository

typealias ConsentRepository = sotaos.domain.rights.ConsentRepository

interface KnowledgeRepository {
    fun save(knowledge: Knowledge): Knowledge
    fun findById(id: KnowledgeId): Knowledge?
}

/**
 * Injectable clock (ADR-007 source doc §12 Determinism: clock must be
 * injectable, not `Instant.now()` scattered through the codebase).
 */
fun interface Clock {
    fun now(): Instant
}

/** Injectable id generator, same determinism rationale. */
fun interface IdGenerator {
    fun next(): String
}
