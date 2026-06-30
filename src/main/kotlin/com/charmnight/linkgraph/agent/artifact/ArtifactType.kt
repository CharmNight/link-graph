package com.charmnight.linkgraph.agent.artifact

/**
 * 统一描述 runtime 中间产物的类型。
 *
 * 第一阶段先覆盖问答与后续规划会用到的核心分类，避免继续依赖完整会话历史传递状态。
 * 每种类型对应一个 capability 产出的"半成品"，后续 capability 可以按类型检索并消费。
 */
enum class ArtifactType {
    /** 图摘要：把图压缩为可读的结构化总结。 */
    GRAPH_SUMMARY,

    /** 图差异：两个图版本之间的差异描述。 */
    GRAPH_DIFF,

    /** 代码证据：从代码中提取出的支撑结论的事实片段。 */
    CODE_EVIDENCE,

    /** QA 证据链：问答过程中积累的证据追溯。 */
    QA_EVIDENCE_TRACE,

    /** QA 结论：问答最终给出的结论性输出。 */
    QA_CONCLUSION,

    /** 候选草稿：尚未确认的改动建议。 */
    CANDIDATE_DRAFT,

    /** 已确认意图：用户确认过的业务意图，作为后续生成的基础。 */
    CONFIRMED_INTENT,

    /** 实现计划：把意图拆解为步骤的计划文档。 */
    PLAN,

    /** 代码草稿：根据计划生成的具体代码 diff。 */
    CODE_DRAFT,
}
