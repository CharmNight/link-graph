package com.charmnight.linkgraph.architecture.query

/**
 * 项目语义种子索引的一条记录。
 *
 * 种子是预先计算的"节点 → 文本"映射，用于在没有 embedding 模型时做关键词检索。
 * 通过 textHash 让缓存键稳定（不依赖具体文本编码）。
 */
data class ProjectSemanticSeedRecord(
    /** 节点 ID。 */
    val nodeId: String,
    /** 种子文本（用于检索匹配）。 */
    val text: String,
    /** 文本哈希；用作缓存键。 */
    val textHash: String,
    /** 生成该种子所用的 embedding 模型 ID。 */
    val embeddingModelId: String,
    /** 所属 slice ID；用于增量失效。 */
    val sliceId: String,
    /** 记录 schema 版本；不匹配时需要重建。 */
    val schemaVersion: Int = 1,
)

/** 单次种子检索结果。 */
data class ProjectSemanticSeedResult(
    /** 命中的节点 ID。 */
    val nodeId: String,
    /** 命中得分（命中的词数越多分越高）。 */
    val score: Int,
    /** 命中元数据；包含证据来源、文本哈希等。 */
    val metadata: Map<String, String>,
)

/**
 * 项目语义种子索引。
 *
 * 一种轻量级检索方案：不依赖向量数据库，只做关键词包含匹配。
 * 适合在 embedding 服务不可用时作为兜底，让符号搜索仍能给出合理结果。
 */
class ProjectSemanticSeedIndex(
    /** 是否启用；未启用时所有查询返回空。 */
    private val enabled: Boolean = false,
    /** 种子记录列表。 */
    private val records: List<ProjectSemanticSeedRecord> = emptyList(),
) {
    /**
     * 关键词检索。
     *
     * @param query 查询字符串；按空白切分为多个关键词
     * @param topK 最多返回的结果数
     * @return 按命中得分降序的结果列表
     */
    fun search(query: String, topK: Int = 8): List<ProjectSemanticSeedResult> {
        // 未启用直接返回空，避免无意义计算
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
                // 得分 = 命中的词数
                val score = terms.count { term -> text.contains(term) }
                if (score <= 0) {
                    null
                } else {
                    ProjectSemanticSeedResult(
                        nodeId = record.nodeId,
                        score = score,
                        // 元数据让上层能识别这是种子证据，按"仅召回"角色处理
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
            // 主排序：得分降序；次排序：节点 ID 升序保证稳定
            .sortedWith(compareByDescending<ProjectSemanticSeedResult> { it.score }.thenBy { it.nodeId })
            // topK 至少为 1，避免参数误传
            .take(topK.coerceAtLeast(1))
    }
}
