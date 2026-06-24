package com.charmnight.linkgraph.semantic.model

/**
 * 表示语义分析在某个边界处的截断或限制信息。
 *
 * 语义分析常常不可能无限制展开（例如递归调用深度、跨模块跳转次数），
 * 在触达边界时用本结构记录"为什么停下来了"，便于 UI 提示用户。
 */
data class SemanticBoundary(
    /** 保存边界标题。面向用户的简短描述，例如"已达调用深度上限"。 */
    val title: String,
    /** 保存触发边界的原因说明。 */
    val reason: String,
    /** 保存边界类型。用于后续按类型决定是否允许用户手动突破。 */
    val kind: String,
)
