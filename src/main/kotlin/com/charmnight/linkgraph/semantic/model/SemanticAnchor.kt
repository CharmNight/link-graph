package com.charmnight.linkgraph.semantic.model

/**
 * 表示语义图中的锚点信息。
 *
 * 锚点用于把用户的注意力固定到某个语义单元（例如"当前正在解释的类"），
 * 后续的展开、追问都以锚点为参照。多个视图可以共享同一个锚点，
 * 保证跨视图的语义一致。
 */
data class SemanticAnchor(
    /** 保存锚点的唯一标识。 */
    val id: String,
    /** 保存锚点指向的语义单元标识；缺失时为 null 表示自由锚点（无具体目标）。 */
    val targetUnitId: String? = null,
    /** 保存锚点展示标签。 */
    val label: String? = null,
)
