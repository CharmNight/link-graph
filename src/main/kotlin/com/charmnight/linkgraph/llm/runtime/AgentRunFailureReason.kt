package com.charmnight.linkgraph.llm.runtime

/**
 * 统一定义 runtime 的失败或停止原因。
 *
 * 这些原因既要能驱动流程停止，也要能映射回日志和 UI，可解释为何本轮没有继续执行。
 * 区分不同原因便于上层做不同处理（例如超出预算 vs 证据不足）。
 */
enum class AgentRunFailureReason {
    /** 已达到最大步数限制。 */
    MAX_STEPS_EXCEEDED,

    /** 读取的文件数超出预算。 */
    MAX_FILES_READ_EXCEEDED,

    /** 提取的代码片段数超出预算。 */
    MAX_SNIPPETS_EXCEEDED,

    /** 单个代码片段的行数超出预算。 */
    MAX_SNIPPET_LINES_EXCEEDED,

    /** 所有片段累计行数超出预算。 */
    MAX_TOTAL_SNIPPET_LINES_EXCEEDED,

    /** 运行总耗时超出预算（秒）。 */
    MAX_RUNTIME_SECONDS_EXCEEDED,

    /** 收集到的证据不足以支持 capability 做出结论。 */
    EVIDENCE_INSUFFICIENT,

    /** capability 执行过程中抛出异常或返回失败。 */
    CAPABILITY_EXECUTION_FAILED,

    /** 终止化阶段（finalization）失败，例如产物归档出错。 */
    FINALIZATION_FAILED,
}
