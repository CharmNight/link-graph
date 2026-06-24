package com.charmnight.linkgraph.llm.context

/**
 * 统一描述一次上下文选择结果。
 *
 * 上下文选择器（[ContextSelector]）的输出。第一阶段先收敛为文本段落和命中摘要，
 * 后续再扩展 token 估算等更细粒度指标。这种结构化返回让上层在不依赖具体选择策略的前提下，
 * 就能拿到稳定的展示与传递格式。
 */
data class ContextSelectionResult(
    /** 选中的上下文片段。每条通常是一段可独立阅读的文本（消息、代码块等）。 */
    val sections: List<String> = emptyList(),
    /** 命中摘要。简短描述本次选择得到了什么、为什么被选中，便于上游记录与展示。 */
    val summary: String = "",
)
