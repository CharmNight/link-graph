package com.charmnight.linkgraph.semantic.model

/**
 * 定义语义单元之间的关系类型。
 *
 * 与 [com.charmnight.linkgraph.model.EdgeType] 区别：本枚举是语义分析层抽象，
 * 投影到图时才会映射为具体的 EdgeType。
 */
enum class SemanticRelationKind {
    /** 表示包含关系（A 包含 B）。 */
    CONTAINS,
    /** 表示控制流关系（A 流向 B）。 */
    CONTROL_FLOW,
    /** 表示调用关系（A 调用 B）。 */
    INVOKES,
    /** 表示引用关系（A 引用 B）。 */
    REFERENCES,
    /** 表示绑定关系（A 绑定到 B，例如配置项绑定到字段）。 */
    BINDS_TO,
    /** 表示实现关系（class A implements B）。 */
    IMPLEMENTS,
    /** 表示文档说明关系（文档 A 描述 B）。 */
    DOCUMENTS,
}

/**
 * 控制流边的结构化角色。
 *
 * 让控制流边可以表达"这是 if 的真分支"、"这是循环回边"等语义，
 * 而不是单纯的一条普通边。
 */
enum class FlowEdgeRole {
    /** 入口边。 */
    ENTRY,
    /** 普通边。 */
    NORMAL,
    /** if 的真分支。 */
    TRUE_BRANCH,
    /** if 的假分支。 */
    FALSE_BRANCH,
    /** 循环体边。 */
    LOOP_BODY,
    /** 循环退出边。 */
    LOOP_EXIT,
    /** 循环回边（回到循环头）。 */
    LOOP_BACK,
    /** 循环更新边（for 中的 i++）。 */
    LOOP_UPDATE,
    /** 异常路径边。 */
    EXCEPTION,
    /** finally 块边。 */
    FINALLY,
    /** switch 的某个 case 分支。 */
    CASE,
    /** switch 的 default 分支。 */
    DEFAULT,
    /** switch 的 fall-through 边。 */
    FALLTHROUGH,
}

/** 控制流边的来源：来自语义分析 vs 投影时合成。 */
enum class FlowEdgeProvenance {
    /** 来自语义分析（基于代码事实）。 */
    SEMANTIC_ANALYSIS,
    /** 投影时为了图的可读性合成的边（非真实代码关系）。 */
    SYNTHETIC_PROJECTION,
}

/**
 * 表示两个语义单元之间的一条关系。
 *
 * 这条关系既可能是显式代码事实（CALLS、IMPLEMENTS 等），
 * 也可能是控制流（带 flowEdgeRole）、合成的投影边（synthetic=true）。
 * 灵活的字段集合让它能表达多种语义。
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
    /** 保存控制流边的结构化语义角色；非控制流边为 null。 */
    val flowEdgeRole: FlowEdgeRole? = null,
    /** 标记当前控制流边是否语义不完整（例如未完全展开的 switch）。 */
    val incomplete: Boolean = false,
    /** 标记当前边是否为投影时补出的合成边。 */
    val synthetic: Boolean = false,
    /** 保存当前边的来源。 */
    val provenance: FlowEdgeProvenance = FlowEdgeProvenance.SEMANTIC_ANALYSIS,
    /** 保存来自底层索引或解析器的证据元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)
