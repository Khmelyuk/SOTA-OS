package sotaos.application.services

import sotaos.application.ports.KnowledgeRepository
import sotaos.domain.memory.Knowledge
import sotaos.domain.shared.KnowledgeId

/** Run inside the repository transaction. Existing versions permit lifecycle status changes only. */
fun KnowledgeRepository.checkKnowledgeWrite(value: Knowledge) {
    val existing = findById(value.id)
    if (existing != null) {
        require(existing.copy(status = value.status) == value) { "Knowledge versions are immutable." }
    } else {
        checkKnowledgeLineage(value)
        value.supersedes?.let { parent ->
            require(findSuccessors(parent).isEmpty()) { "Knowledge already has a local successor." }
        }
    }
}

/** Legacy roots may retain positive version numbers without inventing unavailable ancestors. */
fun KnowledgeRepository.checkKnowledgeLineage(value: Knowledge) {
    val seen = mutableSetOf<KnowledgeId>()
    var current = value
    while (true) {
        require(seen.add(current.id)) { "Knowledge lineage contains a cycle." }
        require(current.version > 0) { "Knowledge version must be positive." }
        val parentId = current.supersedes ?: break
        val parent = requireNotNull(findById(parentId)) { "Knowledge predecessor is missing." }
        require(parent.version < Int.MAX_VALUE && current.version == parent.version + 1) {
            "Knowledge version must immediately follow its predecessor."
        }
        require(current.context == parent.context && current.provenance.author == parent.provenance.author) {
            "Knowledge lineage must retain its context and author."
        }
        current = parent
    }
}
