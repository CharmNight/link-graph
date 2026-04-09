package com.charmnight.linkgraph.semantic.policy

/**
 * 控制语义分析需要捕获哪些类型的信息。
 */
data class SemanticCapturePolicy(
    /** 控制是否捕获控制流信息。 */
    val includeControlFlow: Boolean = true,
    /** 控制是否捕获调用关系。 */
    val includeInvocations: Boolean = true,
    /** 控制是否捕获异常路径。 */
    val includeExceptionPath: Boolean = true,
    /** 控制是否捕获资源引用关系。 */
    val includeResourceReferences: Boolean = true,
)
