package com.charmnight.linkgraph.architecture.query

data class ProjectSemanticSeedRecord(
    val nodeId: String,
    val text: String,
    val textHash: String,
    val embeddingModelId: String,
    val sliceId: String,
    val schemaVersion: Int = 1,
)

data class ProjectSemanticSeedResult(
    val nodeId: String,
    val score: Int,
    val metadata: Map<String, String>,
)

class ProjectSemanticSeedIndex(
    private val enabled: Boolean = false,
    private val records: List<ProjectSemanticSeedRecord> = emptyList(),
) {
    fun search(query: String, topK: Int = 8): List<ProjectSemanticSeedResult> {
        if (!enabled) {
            return emptyList()
        }
        val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) {
            return emptyList()
        }
        return records
            .mapNotNull { record ->
                val text = record.text.lowercase()
                val score = terms.count { term -> text.contains(term) }
                if (score <= 0) {
                    null
                } else {
                    ProjectSemanticSeedResult(
                        nodeId = record.nodeId,
                        score = score,
                        metadata = mapOf(
                            "evidence.kind" to "SEMANTIC_SEED",
                            "evidence.role" to "RECALL_ONLY",
                            "semantic.textHash" to record.textHash,
                            "semantic.embeddingModelId" to record.embeddingModelId,
                            "semantic.sliceId" to record.sliceId,
                        ),
                    )
                }
            }
            .sortedWith(compareByDescending<ProjectSemanticSeedResult> { it.score }.thenBy { it.nodeId })
            .take(topK.coerceAtLeast(1))
    }
}
