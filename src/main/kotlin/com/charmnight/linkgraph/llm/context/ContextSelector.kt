package com.charmnight.linkgraph.llm.context

/**
 * 统一上下文选择接口。
 *
 * 把"如何根据输入挑选上下文"这件事抽象成一个函数式接口，
 * 让不同的策略（按 token 截断、按相关度排序、按主题筛选等）都可以以同一签名实现，
 * 上层注入具体策略即可，不需要修改调用方。
 *
 * 类型参数 [T] 表示输入上下文（例如候选消息列表、节点列表等）。
 */
fun interface ContextSelector<T> {
    /**
     * 根据输入做一次选择，返回选择结果（包含保留项与统计信息）。
     */
    fun select(input: T): ContextSelectionResult
}
