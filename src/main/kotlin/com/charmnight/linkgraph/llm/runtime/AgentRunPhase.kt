package com.charmnight.linkgraph.llm.runtime

/**
 * 描述一次 Agent Run 的阶段。
 *
 * M1 先覆盖创建、运行、成功和失败四种核心终态，后续再按需要补充更细的中间阶段。
 * 该枚举作为状态机的离散状态，UI 与日志通过它快速判断 run 当前处于哪一步。
 */
enum class AgentRunPhase {
    /** 已创建但尚未开始执行；run 处于初始化阶段。 */
    CREATED,

    /** 正在执行；run 持有活动 step executor。 */
    RUNNING,

    /** 已成功完成；产物已落库。 */
    SUCCEEDED,

    /** 已失败；可能附带失败原因记录在 run 状态中。 */
    FAILED,
}
