package com.charmnight.linkgraph.extract

/**
 * 表示链路提取阶段遇到的边界信息。
 */
data class ExtractionBoundary(
    /** 保存边界标题。 */
    val title: String,
    /** 保存触发边界的原因。 */
    val reason: String,
    /** 保存边界类型。 */
    val kind: String,
)
