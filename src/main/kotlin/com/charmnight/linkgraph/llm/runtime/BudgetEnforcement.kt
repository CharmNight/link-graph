package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.llm.artifact.ArtifactRef

/**
 * 在读取下一个文件之前判断是否已触及预算上限。
 *
 * Coordinator 在每次读取新文件前都应调用本函数，
 * 返回非 null 时立即停止并给出对应的失败原因。
 */
internal fun RunBudget.failureReasonBeforeNextFileRead(): AgentRunFailureReason? {
    return when {
        // 已读文件数达上限
        filesRead >= maxFilesRead -> AgentRunFailureReason.MAX_FILES_READ_EXCEEDED
        // 已读片段数达上限
        snippetsRead >= maxSnippets -> AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED
        // 累计片段行数达上限
        totalSnippetLinesRead >= maxTotalSnippetLines -> AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED
        else -> null
    }
}

/**
 * 用给定预算对状态求值失败原因。
 * 与 [StopPolicy.evaluate] 等价，但便于在不修改状态的情况下做"试探性评估"。
 */
internal fun StopPolicy.failureReasonForBudget(
    state: AgentRunState,
    budget: RunBudget,
): AgentRunFailureReason? {
    return evaluate(state.copy(budget = budget))
}

/**
 * 构造一个表示"预算超限"的失败 step 结果。
 *
 * Coordinator 在检测到预算命中时统一调用本函数，保证：
 * - 状态中的 phase 切到 FAILED；
 * - stepIndex 推进；
 * - 添加一条 step 记录便于事后排查；
 * - 写入失败原因与最近一次模型输出。
 *
 * @param state 当前 run 状态
 * @param budget 触发命中的预算
 * @param failureReason 失败原因枚举
 * @param summary 失败摘要文案
 * @param lastModelOutput 最近一次模型输出
 * @param artifactRefs 本 step 后的产物引用列表
 * @param toolName 关联工具名；可空
 * @param nodeId 关联节点 ID；可空
 */
internal fun budgetExceededStepResult(
    state: AgentRunState,
    budget: RunBudget,
    failureReason: AgentRunFailureReason,
    summary: String,
    lastModelOutput: String,
    artifactRefs: List<ArtifactRef> = state.artifactRefs,
    toolName: String? = null,
    nodeId: String? = null,
): AgentStepExecutionResult.Fail {
    return AgentStepExecutionResult.Fail(
        state.copy(
            phase = AgentRunPhase.FAILED,
            budget = budget,
            // 即使失败也要推进 stepIndex，避免重试时重复同一步
            stepIndex = state.stepIndex + 1,
            artifactRefs = artifactRefs,
            stepRecords = state.stepRecords + AgentStepRecord(
                stepIndex = state.stepIndex,
                phase = AgentRunPhase.FAILED,
                summary = summary,
                toolName = toolName,
                nodeId = nodeId,
            ),
            failureReason = failureReason,
            lastModelOutput = lastModelOutput,
        ),
    )
}
