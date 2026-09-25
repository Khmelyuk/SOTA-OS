package sotaos.test.fakes

import sotaos.application.ports.*
import sotaos.domain.identity.*
import sotaos.domain.collective.*
import sotaos.domain.relation.*
import sotaos.domain.agency.*
import sotaos.domain.memory.*
import sotaos.domain.shared.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory port implementations, used by T1/T2/T4/acceptance tests
 * (ADR-007 test tier table). NOT used for T3 integration tests, which
 * require the real SQLDelight/SQLite adapters (see persistence/README.md).
 *
 * These fakes contain NO business logic beyond storage — any check that
 * belongs in a Service class must live there, not here, or the test
 * would stop being a real test of the architecture invariant.
 */

class FixedClock(private var instant: Instant = Instant.parse("2026-08-27T00:00:00Z")) : Clock {
    override fun now(): Instant = instant
    fun advance(seconds: Long) { instant = instant.plusSeconds(seconds) }
}

class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private val counter = AtomicInteger(0)
    override fun next(): String = "$prefix-${counter.incrementAndGet()}"
}

class InMemoryPersonRepository : PersonRepository {
    private val store = mutableMapOf<PersonId, Person>()
    override fun save(person: Person): Person { store[person.id] = person; return person }
    override fun findById(id: PersonId): Person? = store[id]
}

class InMemoryIdentityRepository : IdentityRepository {
    private val store = mutableMapOf<IdentityId, Identity>()
    override fun save(identity: Identity): Identity { store[identity.id] = identity; return identity }
    override fun findByPerson(person: PersonId): List<Identity> = store.values.filter { it.ownerPerson == person }
}

class InMemoryCoreRepository : CoreRepository {
    private val store = mutableMapOf<CoreId, Core>()
    override fun save(core: Core): Core { store[core.id] = core; return core }
    override fun findById(id: CoreId): Core? = store[id]
}

class InMemoryMembershipRepository : MembershipRepository {
    private val store = mutableListOf<Membership>()
    override fun save(membership: Membership): Membership { store.add(membership); return membership }
    override fun findActiveFor(subject: PersonId, collective: SubjectRef): Membership? =
        store.lastOrNull { it.subject == subject && it.collective == collective && it.state == LifecycleState.ACTIVE }
}

class InMemoryAuthorityRepository : AuthorityRepository {
    private val store = mutableMapOf<AuthorityId, Authority>()
    override fun save(authority: Authority): Authority { store[authority.id] = authority; return authority }
    override fun findById(id: AuthorityId): Authority? = store[id]
    override fun findActiveFor(subject: SubjectRef, context: Context): List<Authority> =
        store.values.filter { it.subject == subject && it.context == context && it.state == LifecycleState.ACTIVE }
}

class InMemoryMissionRepository : MissionRepository {
    private val store = mutableMapOf<MissionId, Mission>()
    override fun save(mission: Mission): Mission { store[mission.id] = mission; return mission }
    override fun findById(id: MissionId): Mission? = store[id]
}

class InMemoryDecisionRepository : DecisionRepository {
    private val store = mutableListOf<Decision>()
    override fun save(decision: Decision): Decision { store.add(decision); return decision }
    override fun findById(id: DecisionId): Decision? = store.lastOrNull { it.id == id }
}

class InMemoryActionRepository : ActionRepository {
    private val store = mutableListOf<Action>()
    override fun save(action: Action): Action { store.add(action); return action }
}

class InMemoryResultRepository : ResultRepository {
    private val store = mutableListOf<Result>()
    override fun save(result: Result): Result { store.add(result); return result }
}

/**
 * Append-only by construction: this class has no method that removes
 * or mutates an existing entry from `store`.
 */
class InMemoryEventStore : EventStore {
    private val store = mutableMapOf<EventId, Event>()
    override fun append(event: Event): Event {
        require(!store.containsKey(event.id)) { "Event ${event.id.value} already recorded — append-only violation." }
        store[event.id] = event
        return event
    }
    override fun findById(id: EventId): Event? = store[id]
    override fun findByActor(actor: SubjectRef): List<Event> = store.values.filter { it.actor == actor }
}

class InMemoryExperienceRepository : ExperienceRepository {
    private val store = mutableListOf<Experience>()
    override fun save(experience: Experience): Experience { store.add(experience); return experience }
}

class InMemoryKnowledgeRepository : KnowledgeRepository {
    private val store = mutableMapOf<KnowledgeId, Knowledge>()
    override fun save(knowledge: Knowledge): Knowledge { store[knowledge.id] = knowledge; return knowledge }
    override fun findById(id: KnowledgeId): Knowledge? = store[id]
}
