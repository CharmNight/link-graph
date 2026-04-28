package com.charmnight.linkgraph.llm.context

/**
 * 统一上下文选择接口。
 */
fun interface ContextSelector<T> {
    fun select(input: T): ContextSelectionResult
}
