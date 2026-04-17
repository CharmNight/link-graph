package com.charmnight.linkgraph.llm.context

/**
 * 统一描述一次上下文选择结果。
 * 第一阶段先收敛为文本段落和命中摘要，后续再扩展 token 估算等更细粒度指标。
 */
data class ContextSelectionResult(
    /** 选中的上下文片段。 */
    val sections: List<String> = emptyList(),
    /** 命中摘要。 */
    val summary: String = "",
)
