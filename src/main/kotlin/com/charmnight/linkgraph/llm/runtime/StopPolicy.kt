package com.charmnight.linkgraph.llm.runtime

/**
 * 统一封装 run 的停止条件。
 * coordinator 每个 step 前都必须调用它，确保预算命中时立即收敛，而不是继续盲跑。
 */
data class StopPolicy(
    /** 证据不足时是否直接停止。 */
    val stopWhenEvidenceInsufficient: Boolean = false,
) {
    /**
     * 返回 null 表示可以继续执行，返回 failure reason 表示必须停止。
     */
    fun evaluate(
        state: AgentRunState,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AgentRunFailureReason? {
        val budget = state.budget
        return when {
            budget.usedSteps > budget.maxSteps -> AgentRunFailureReason.MAX_STEPS_EXCEEDED
            budget.filesRead > budget.maxFilesRead -> AgentRunFailureReason.MAX_FILES_READ_EXCEEDED
            budget.snippetsRead > budget.maxSnippets -> AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED
            budget.snippetLineLimitExceeded -> AgentRunFailureReason.MAX_SNIPPET_LINES_EXCEEDED
            budget.totalSnippetLinesRead > budget.maxTotalSnippetLines -> AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED
            budget.elapsedSeconds(nowEpochMillis) > budget.maxRuntimeSeconds -> AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED
            stopWhenEvidenceInsufficient && state.failureReason == AgentRunFailureReason.EVIDENCE_INSUFFICIENT ->
                AgentRunFailureReason.EVIDENCE_INSUFFICIENT
            else -> null
        }
    }

    companion object {
        /** 第一阶段默认停止策略，仅启用预算硬限制。 */
        fun default(): StopPolicy = StopPolicy()
    }
}
