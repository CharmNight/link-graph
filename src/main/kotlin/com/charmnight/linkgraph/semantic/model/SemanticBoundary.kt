package com.charmnight.linkgraph.semantic.model

/**
 * 表示语义分析在某个边界处的截断或限制信息。
 */
data class SemanticBoundary(
    /** 保存边界标题。 */
    val title: String,
    /** 保存触发边界的原因说明。 */
    val reason: String,
    /** 保存边界类型。 */
    val kind: String,
)
