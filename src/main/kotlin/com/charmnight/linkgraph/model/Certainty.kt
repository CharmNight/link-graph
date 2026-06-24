package com.charmnight.linkgraph.model

/**
 * 表示链路图元素结论的可信程度。
 *
 * 用于标注节点/边的结论是来自确凿事实、规则推导还是模型猜测，
 * 让 UI 在展示时给出不同的可信度暗示，也方便后续过滤"仅显示已确认"等视图。
 */
enum class Certainty {
    /** 表示结论直接来自确定的代码事实，例如 PSI 解析得到的类继承关系。 */
    PROVEN,

    /** 表示结论由规则推导得到，例如根据命名约定猜测的 Spring Bean。 */
    RULE_INFERRED,

    /** 表示结论由 LLM 生成，仍需人工确认，例如大模型给出的可能调用关系。 */
    LLM_SUGGESTED,
}
