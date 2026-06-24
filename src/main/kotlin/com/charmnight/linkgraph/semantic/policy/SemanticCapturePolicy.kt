package com.charmnight.linkgraph.semantic.policy

/**
 * 控制语义分析需要捕获哪些类型的信息。
 *
 * 语义分析可能产出多种关系（控制流、调用、异常、资源引用等），
 * 本策略让上层可以按需开关每种信息的捕获，避免在只想看调用关系时把控制流也全量算出来。
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
