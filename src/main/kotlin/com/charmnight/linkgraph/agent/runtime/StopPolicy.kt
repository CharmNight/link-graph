package com.charmnight.linkgraph.agent.runtime

/**
 * 统一封装 run 的停止条件。
 *
 * coordinator 每个 step 前都必须调用它，确保预算命中时立即收敛，而不是继续盲跑。
 * 这种"集中检查"避免每个 step 各自实现预算判断，也便于添加新的停止规则。
 */
data class StopPolicy(
    /** 证据不足时是否直接停止。开启后 coordinator 在收到 EVIDENCE_INSUFFICIENT 时立即结束。 */
    val stopWhenEvidenceInsufficient: Boolean = false,
    /** 证据读取预算命中时是否直接停止整个 run。 */
    val stopWhenEvidenceReadBudgetReached: Boolean = true,
) {
    /**
     * 评估当前状态是否应该停止。
     *
     * 返回 null 表示可以继续执行，返回 failure reason 表示必须停止。
     *
     * 预算上限是包含式语义：当已消耗计数等于 max 值时，coordinator 必须在
     * 启动下一个 step 或资源读取前停止。
     *
     * @param state 当前 run 状态
     * @param nowEpochMillis 当前时间戳；用于计算运行时长
     */
    fun evaluate(
        state: AgentRunState,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AgentRunFailureReason? {
        val budget = state.budget
        return when {
            // 步数上限：最常见、最先检查
            budget.usedSteps >= budget.maxSteps -> AgentRunFailureReason.MAX_STEPS_EXCEEDED
            // 文件读取上限
            stopWhenEvidenceReadBudgetReached &&
                resourceBudgetReached(budget.filesRead, budget.maxFilesRead) -> AgentRunFailureReason.MAX_FILES_READ_EXCEEDED
            // 片段读取上限
            stopWhenEvidenceReadBudgetReached &&
                resourceBudgetReached(budget.snippetsRead, budget.maxSnippets) -> AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED
            // 单片段行数上限
            stopWhenEvidenceReadBudgetReached &&
                budget.snippetLineLimitExceeded -> AgentRunFailureReason.MAX_SNIPPET_LINES_EXCEEDED
            // 累计片段行数上限
            stopWhenEvidenceReadBudgetReached &&
                resourceBudgetReached(budget.totalSnippetLinesRead, budget.maxTotalSnippetLines) ->
                AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED
            // 总耗时上限
            budget.elapsedSeconds(nowEpochMillis) >= budget.maxRuntimeSeconds -> AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED
            // 证据不足：仅当显式开启时生效
            stopWhenEvidenceInsufficient && state.failureReason == AgentRunFailureReason.EVIDENCE_INSUFFICIENT ->
                AgentRunFailureReason.EVIDENCE_INSUFFICIENT
            else -> null
        }
    }

    /** 判断"已消耗 >= 上限"。 */
    private fun resourceBudgetReached(consumed: Int, limit: Int): Boolean {
        return consumed >= limit
    }

    companion object {
        /** 第一阶段默认停止策略，仅启用预算硬限制。 */
        fun default(): StopPolicy = StopPolicy()
    }
}
