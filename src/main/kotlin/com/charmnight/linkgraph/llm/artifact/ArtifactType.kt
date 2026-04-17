package com.charmnight.linkgraph.llm.artifact

/**
 * 统一描述 runtime 中间产物的类型。
 * 第一阶段先覆盖问答与后续规划会用到的核心分类，避免继续依赖完整会话历史传递状态。
 */
enum class ArtifactType {
    GRAPH_SUMMARY,
    GRAPH_DIFF,
    CODE_EVIDENCE,
    QA_CONCLUSION,
    CANDIDATE_DRAFT,
    CONFIRMED_INTENT,
    PLAN,
    CODE_DRAFT,
}
