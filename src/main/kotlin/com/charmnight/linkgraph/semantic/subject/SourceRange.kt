package com.charmnight.linkgraph.semantic.subject

/**
 * 表示源码中的一段范围。
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
