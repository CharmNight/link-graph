package com.charmnight.linkgraph.semantic.model

/**
 * 表示语义图中的锚点信息。
 */
data class SemanticAnchor(
    /** 保存锚点的唯一标识。 */
    val id: String,
    /** 保存锚点指向的语义单元标识。 */
    val targetUnitId: String? = null,
    /** 保存锚点展示标签。 */
    val label: String? = null,
)
