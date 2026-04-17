package com.charmnight.linkgraph.llm.artifact

/**
 * 产物摘要只暴露给 runtime 与日志，用于在不展开完整内容时快速判断当前 run 已沉淀了什么证据。
 */
data class ArtifactSummary(
    /** 摘要标题。 */
    val title: String,
    /** 摘要说明。 */
    val description: String? = null,
)
