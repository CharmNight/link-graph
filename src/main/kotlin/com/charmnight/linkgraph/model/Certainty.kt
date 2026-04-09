package com.charmnight.linkgraph.model

/**
 * 表示链路图元素结论的可信程度。
 */
enum class Certainty {
    /** 表示结论直接来自确定的代码事实。 */
    PROVEN,
    /** 表示结论由规则推导得到。 */
    RULE_INFERRED,
    /** 表示结论由 LLM 生成，仍需人工确认。 */
    LLM_SUGGESTED,
}
