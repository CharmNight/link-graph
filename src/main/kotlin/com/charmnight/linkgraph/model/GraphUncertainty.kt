package com.charmnight.linkgraph.model

/**
 * 表示图元素仍存在待确认的不确定信息。
 */
data class GraphUncertainty(
    /** 记录不确定性的原因说明。 */
    val reason: String,
    /** 记录可选的置信度分值。 */
    val confidence: Double? = null,
)
