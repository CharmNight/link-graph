package com.charmnight.linkgraph.llm.runtime

/**
 * 描述一次 Agent Run 的阶段。
 * M1 先覆盖创建、运行、成功和失败四种核心终态，后续再按需要补充更细的中间阶段。
 */
enum class AgentRunPhase {
    CREATED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}
