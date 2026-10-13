package sotaos.agent.research

import kotlinx.serialization.json.*
import java.time.Instant

/** Human-invoked research workspace. No Action, canonicalization, delegation or publishing API. */
class ResearchAgent(
    private val journal: ResearchJournal,
    private val authorization: ResearchAuthorization,
    private val now: () -> Instant = Instant::now
) {
    private val audit = ResearchAuditWriter(now)

    fun ingest(access: ResearchAccess, discussions: List<PublicDiscussion>): List<String> =
        command(access, "research.ingest") { records, session ->
            val ids = ingest(records, session, access, discussions)
            audit.append(records, session, access, "INGEST", buildJsonObject {
                put("sourceIds", JsonArray(ids.map(::JsonPrimitive)))
            })
            ids
        }

    fun fetchMoltbook(access: ResearchAccess, adapter: MoltbookAdapter): FetchStatus =
        command(access, "research.ingest") { records, session ->
            val result = adapter.fetch()
            if (result.status == FetchStatus.OK && result.discussions.isNotEmpty()) {
                ingest(records, session, access, result.discussions)
            }
            audit.append(records, session, access, "FETCH", buildJsonObject { put("status", result.status.name) })
            result.status
        }

    fun addArgument(access: ResearchAccess, sourceId: String, kind: ArgumentKind,
        text: String, quote: String, counters: String? = null): String =
        command(access, "research.analyze") { records, session ->
            require(text.length in 1..MAX_TEXT && quote.length in 1..MAX_TEXT)
            val source = records.single { it.field("id") == sourceId && it.field("operation") == "SOURCE" }
            require(quote in source.data().field("text")) { "Evidence must be an exact source quotation." }
            if (kind == ArgumentKind.COUNTERARGUMENT) {
                require(records.any { it.field("id") == counters && it.field("operation") == "ARGUMENT" })
            } else {
                require(counters == null)
            }
            audit.append(records, session, access, "ARGUMENT", buildJsonObject {
                put("sourceId", sourceId)
                put("kind", kind.name)
                put("text", text)
                put("quote", quote)
                put("counters", counters?.let(::JsonPrimitive) ?: JsonNull)
                put("status", "KNOWLEDGE_CANDIDATE")
            })
        }

    fun search(access: ResearchAccess, query: String): List<String> =
        command(access, "research.read") { records, session ->
            require(query.length in 1..MAX_FIELD)
            val matches = records.filter {
                it.field("operation") == "SOURCE" &&
                    (it.data().field("text").contains(query, ignoreCase = true) ||
                        it.data().field("title").contains(query, ignoreCase = true))
            }.map { it.field("id") }
            audit.append(records, session, access, "SEARCH", buildJsonObject {
                put("queryHash", digest(query))
                put("resultCount", matches.size)
            })
            matches
        }

    fun report(access: ResearchAccess): String = command(access, "research.read") { records, session ->
        val report = UkrainianReport.render(records)
        audit.append(records, session, access, "REPORT", buildJsonObject { put("contentHash", digest(report)) })
        report
    }

    fun createDraft(access: ResearchAccess, text: String, evidence: List<String>): String =
        command(access, "research.draft") { records, session ->
            require(text.length in 1..MAX_TEXT && evidence.isNotEmpty() && evidence.size <= MAX_BATCH)
            require(evidence.distinct().size == evidence.size)
            require(evidence.all { id -> records.any {
                it.field("id") == id && it.field("operation") in setOf("SOURCE", "ARGUMENT")
            } })
            audit.append(records, session, access, "DRAFT", buildJsonObject {
                put("text", text)
                put("contentHash", digest(text))
                put("evidence", JsonArray(evidence.map(::JsonPrimitive)))
                put("status", "DRAFT_ONLY")
            })
        }

    /** Records human review of the exact immutable draft. It grants no authority to publish. */
    fun approveDraft(access: ResearchAccess, draftId: String, expectedHash: String): String =
        command(access, "research.approve") { records, session ->
            val draft = records.single { it.field("id") == draftId && it.field("operation") == "DRAFT" }
            require(draft.data().field("contentHash") == expectedHash)
            audit.append(records, session, access, "APPROVAL", buildJsonObject {
                put("draftId", draftId)
                put("contentHash", expectedHash)
                put("status", "REVIEWED_DRAFT_ONLY")
            })
        }

    /** Trusted local UI must show the exact immutable text before returning true. */
    fun reviewDraft(access: ResearchAccess, draftId: String, confirm: (DraftPreview) -> Boolean): String =
        command(access, "research.approve") { records, session ->
            val draft = records.single { it.field("id") == draftId && it.field("operation") == "DRAFT" }.data()
            val preview = DraftPreview(draftId, draft.field("text"), draft.field("contentHash"))
            require(confirm(preview)) { "Draft review cancelled; approval not recorded." }
            audit.append(records, session, access, "APPROVAL", buildJsonObject {
                put("draftId", draftId)
                put("contentHash", preview.contentHash)
                put("status", "REVIEWED_DRAFT_ONLY")
            })
        }

    private fun ingest(records: MutableList<JsonObject>, session: ResearchSession,
        access: ResearchAccess, discussions: List<PublicDiscussion>): List<String> {
        require(discussions.size in 1..MAX_BATCH)
        return discussions.map { discussion ->
            val payload = discussion.payload()
            records.firstOrNull {
                it.field("operation") == "SOURCE" && it.data() == payload
            }?.field("id") ?: audit.append(records, session, access, "SOURCE", payload)
        }
    }

    private fun <T> command(access: ResearchAccess, operation: String,
        block: (MutableList<JsonObject>, ResearchSession) -> T): T =
        authorization.execute(access, operation, RESOURCE) { session, commit ->
            journal.access(commit) { records -> block(records, session) }
        }

    companion object {
        const val RESOURCE = "research-workspace"
        private const val MAX_BATCH = 20
    }
}
