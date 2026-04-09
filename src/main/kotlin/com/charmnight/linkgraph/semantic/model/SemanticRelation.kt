package com.charmnight.linkgraph.semantic.model

/**
 * 定义语义单元之间的关系类型。
 */
enum class SemanticRelationKind {
    /** 表示包含关系。 */
    CONTAINS,
    /** 表示控制流关系。 */
    CONTROL_FLOW,
    /** 表示调用关系。 */
    INVOKES,
    /** 表示引用关系。 */
    REFERENCES,
    /** 表示绑定关系。 */
    BINDS_TO,
    /** 表示实现关系。 */
    IMPLEMENTS,
    /** 表示文档说明关系。 */
    DOCUMENTS,
}

/**
 * 表示两个语义单元之间的一条关系。
 */
data class SemanticRelation(
    /** 保存关系类型。 */
    val kind: SemanticRelationKind,
    /** 保存起始语义单元标识。 */
    val fromUnitId: String,
    /** 保存目标语义单元标识。 */
    val toUnitId: String,
    /** 保存关系补充标签。 */
    val label: String? = null,
)
