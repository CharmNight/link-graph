package com.charmnight.linkgraph.semantic.model

/**
 * 表示语义分析产出的基础单元。
 */
sealed interface SemanticUnit {
    /** 保存语义单元的唯一标识。 */
    val id: String
    /** 保存语义单元的展示标题。 */
    val title: String
}

/**
 * 表示方法级或类方法级的语义单元。
 */
data class MethodLikeUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
    /** 保存方法签名。 */
    val signature: String,
) : SemanticUnit

/**
 * 表示流程作用域单元，例如条件或循环块。
 */
data class FlowScopeUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
    /** 保存作用域类型。 */
    val scopeKind: String,
) : SemanticUnit

/**
 * 表示流程中的动作步骤单元。
 */
data class FlowActionUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
    /** 保存动作类型。 */
    val actionKind: String,
) : SemanticUnit

/**
 * 表示调用行为对应的语义单元。
 */
data class InvocationUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
    /** 保存目标方法签名。 */
    val targetSignature: String? = null,
) : SemanticUnit

/**
 * 表示外部资源对应的语义单元。
 */
data class ResourceUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
    /** 保存资源类型。 */
    val resourceKind: String,
    /** 保存资源补充元数据。 */
    val metadata: Map<String, String> = emptyMap(),
) : SemanticUnit

/**
 * 表示终止语义单元，例如 return 或 throw。
 */
data class TerminalUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
    /** 保存终止类型。 */
    val terminalKind: String,
) : SemanticUnit

/**
 * 表示流程汇合点单元。
 */
data class MergeUnit(
    /** 保存语义单元标识。 */
    override val id: String,
    /** 保存语义单元标题。 */
    override val title: String,
) : SemanticUnit
