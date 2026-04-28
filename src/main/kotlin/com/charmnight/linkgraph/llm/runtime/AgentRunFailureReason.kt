package com.charmnight.linkgraph.llm.runtime

/**
 * 统一定义 runtime 的失败或停止原因。
 * 这些原因既要能驱动流程停止，也要能映射回日志和 UI，可解释为何本轮没有继续执行。
 */
enum class AgentRunFailureReason {
    MAX_STEPS_EXCEEDED,
    MAX_FILES_READ_EXCEEDED,
    MAX_SNIPPETS_EXCEEDED,
    MAX_SNIPPET_LINES_EXCEEDED,
    MAX_TOTAL_SNIPPET_LINES_EXCEEDED,
    MAX_RUNTIME_SECONDS_EXCEEDED,
    EVIDENCE_INSUFFICIENT,
    CAPABILITY_EXECUTION_FAILED,
    FINALIZATION_FAILED,
}
