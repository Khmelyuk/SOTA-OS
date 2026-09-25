package sotaos.test.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import sotaos.domain.shared.*
import sotaos.domain.sync.*
import sotaos.sync.DeterministicMerge

class SyncConflictPreservationTest : FunSpec({
    test("independent offline logs converge through JSON exchange without losing either event") {
        withNodes { a, b ->
            a.service.recordLocal(record("a-1", a.id))
            b.service.recordLocal(record("b-1", b.id))
            a.service.synchronize(b.id, transportTo(b))
            a.repository.records().map { it.event.id }.toSet() shouldBe setOf(EventId("a-1"), EventId("b-1"))
            a.repository.records().toSet() shouldBe b.repository.records().toSet()
            a.repository.conflicts() shouldBe emptyList()
        }
    }

    test("contradictory authorities retain both events, provenance and authority references as CONTESTED") {
        withNodes { a, b ->
            val first = record("a-1", a.id, claim("grant"))
            val second = record("b-1", b.id, claim("revoke"))
            a.service.recordLocal(first)
            b.service.recordLocal(second)
            a.service.synchronize(b.id, transportTo(b))
            val projection = a.service.projection(sharedAuthority)
            projection.state shouldBe LifecycleState.CONTESTED
            projection.assertions.toSet() shouldBe setOf(first, second)
            projection.conflicts shouldBe listOf(SyncConflict(sharedAuthority, first.event.id, second.event.id))
            projection shouldBe b.service.projection(sharedAuthority)
        }
    }

    test("independent entities, contexts, equivalent terms and adjacent validity windows never conflict") {
        val base = record("base", SotaId("a"), claim("grant", validity = Validity(syncTime, syncTime.plusSeconds(1))))
        val alternatives = listOf(
            claim("revoke", SyncEntity(SyncEntityKind.AUTHORITY, "other")),
            claim("revoke", context = Context("other")),
            claim("grant"),
            claim("revoke", validity = Validity(syncTime.plusSeconds(1), null))
        )
        alternatives.forEach { assertion ->
            DeterministicMerge().conflicts(listOf(base, record("remote", SotaId("b"), assertion))) shouldBe emptyList()
        }
    }

    test("experience observations are additive and divergent knowledge remains contested") {
        for (kind in listOf(SyncEntityKind.EXPERIENCE, SyncEntityKind.KNOWLEDGE)) {
            val entity = SyncEntity(kind, "record")
            val records = listOf(record("a", SotaId("a"), claim("one", entity)),
                record("b", SotaId("b"), claim("two", entity)))
            DeterministicMerge().conflicts(records).size shouldBe if (kind == SyncEntityKind.EXPERIENCE) 0 else 1
        }
    }

    test("causal successors replace a projection head without deleting predecessor history") {
        val first = record("first", SotaId("a"), claim("grant"))
        val middle = record("middle", SotaId("b"), parents = setOf(first.event.id))
        val last = record("last", SotaId("b"), claim("revoke"), setOf(middle.event.id))
        val records = listOf(last, first, middle)
        val merge = DeterministicMerge()
        merge.conflicts(records) shouldBe emptyList()
        merge.project(sharedAuthority, records, emptyList()).assertions shouldBe listOf(last)
        merge.ordered(records).size shouldBe 3
    }

    test("merge and conflict identity do not depend on delivery order or timestamps") {
        val records = listOf(
            record("a", SotaId("a"), claim("one")), record("b", SotaId("b"), claim("two")),
            record("c", SotaId("c"), claim("three"))
        )
        val merge = DeterministicMerge()
        val expected = merge.conflicts(records)
        listOf(records.reversed(), listOf(records[1], records[2], records[0])).forEach {
            merge.conflicts(it) shouldBe expected
            merge.project(sharedAuthority, it, expected) shouldBe merge.project(sharedAuthority, records, expected)
        }
    }

    test("a later descendant does not silently resolve an already recorded conflict") {
        withNodes { a, b ->
            a.service.recordLocal(record("a", a.id, claim("one")))
            b.service.recordLocal(record("b", b.id, claim("two")))
            a.service.synchronize(b.id, transportTo(b))
            a.service.recordLocal(record("later", a.id, claim("chosen"), setOf(EventId("a"), EventId("b"))))
            a.service.projection(sharedAuthority).state shouldBe LifecycleState.CONTESTED
            a.repository.records().size shouldBe 3
            a.repository.conflicts().size shouldBe 1
        }
    }
})
