package com.charmnight.linkgraph.model

/**
 * 表示图元素仍存在待确认的不确定信息。
 *
 * 当某个节点/边的结论不能 100% 确定时（例如反射调用、动态绑定的猜测），
 * 用本结构记录不确定的原因与可选的置信度，让 UI 可以高亮提示用户复核。
 */
data class GraphUncertainty(
    /** 记录不确定性的原因说明。 */
    val reason: String,
    /** 记录可选的置信度分值。范围通常为 [0.0, 1.0]，未给定时为 null。 */
    val confidence: Double? = null,
)
