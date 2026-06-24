package com.charmnight.linkgraph.semantic.subject

/**
 * 表示源码中的一段范围。
 *
 * 同时保存偏移量与行号（行号可选），便于不同场景使用：
 * 偏移量用于精确插入与替换，行号用于面向用户的展示与跳转。
 */
data class SourceRange(
    /** 保存起始偏移量。 */
    val startOffset: Int,
    /** 保存结束偏移量。 */
    val endOffset: Int,
    /** 保存起始行号。 */
    val startLine: Int? = null,
    /** 保存结束行号。 */
    val endLine: Int? = null,
)
