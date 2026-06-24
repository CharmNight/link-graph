package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

/**
 * 复核用例执行结果的密封类型。
 *
 * 区分问答成功完成和问答失败两种情况，供上层视图根据结果类型分发渲染逻辑。
 */
sealed interface ReviewUseCaseResult {
    /** 问答成功完成，携带面向用户的展示模型。 */
    data class QaCompleted(val presentation: QaCompletedResult) : ReviewUseCaseResult
    /** 问答失败，携带失败展示以及用作回退兜底的本地规则结果。 */
    data class QaFailed(
        val presentation: QaFailedResult,
        val fallbackResult: GraphPatchResult,
    ) : ReviewUseCaseResult
}

/**
 * 复核用例：把 LLM runtime 的原始执行结果归一化并转换为对外的展示模型。
 *
 * 内部封装问答结果归一化、失败兜底信息生成等纯逻辑，与 UI 层、状态层解耦。
 *
 * @property normalizeQaResult 根据问答模式对原始结果进行归一化处理的回调
 */
internal class ReviewUseCase(
    private val normalizeQaResult: (GraphPatchResult, QaModeContext) -> GraphPatchResult,
) {
    /**
     * 解析 runtime 执行结果，生成面向复核视图的展示对象。
     *
     * 当 runtime 返回了有效输出时按成功路径包装；若 runtime 未返回输出，则走失败兜底路径，
     * 既生成失败展示也构造本地规则回退结果，避免上层界面拿到空数据。
     *
     * @param runtimeResult runtime 返回的原始结果（含最终状态与输出）
     * @param modeContext 当前问答模式上下文（请求、模式等）
     * @param requestState 异步请求状态快照，用于在结果中携带更新后的状态
     * @param runtimeArtifacts 本次运行产生的运行时产物汇总
     * @param draftValidationState 草稿校验状态（可空）
     * @param codeEligibilityDecision 代码阶段准入判定（可空）
     * @return 成功完成或失败的复核用例结果
     */
    fun resolveQaRuntimeResult(
        runtimeResult: AgentRunResult<GraphPatchResult>,
        modeContext: QaModeContext,
        requestState: AsyncRequestState,
        runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
        draftValidationState: DraftValidationState?,
        codeEligibilityDecision: StageEligibilityDecision?,
    ): ReviewUseCaseResult {
        val output = runtimeResult.output?.let { normalizeQaResult(it, modeContext) }
        if (output == null) {
            val message = qaRuntimeNullOutputMessage(runtimeResult.finalState)
            return ReviewUseCaseResult.QaFailed(
                presentation = QaFailedResult(
                    message = message,
                    requestState = requestState.copy(errorMessage = message),
                    failedRequest = modeContext.request,
                    runtimeArtifacts = runtimeArtifacts,
                ),
                fallbackResult = buildQaRuntimeFailureResult(modeContext, runtimeResult),
            )
        }
        return ReviewUseCaseResult.QaCompleted(
            QaCompletedResult(
                result = output,
                requestState = requestState.copy(promptPreviewAvailable = output.promptPreview.isNotBlank()),
                completedRequest = modeContext.request,
                draftValidationState = draftValidationState,
                codeEligibilityDecision = codeEligibilityDecision,
                runtimeArtifacts = runtimeArtifacts,
            ),
        )
    }

    /**
     * 根据 runtime 终态生成面向用户的失败提示文案。
     *
     * 若存在失败原因码则一并写入括号内；若同时存在模型最后输出文本则进一步附带，
     * 帮助使用者快速定位失败根因。
     *
     * @param runState runtime 执行结束时的最终状态
     * @return 面向用户展示的失败消息
     */
    private fun qaRuntimeNullOutputMessage(runState: AgentRunState): String {
        // 失败原因枚举名，若不存在则使用固定的兜底提示
        val failureReason = runState.failureReason?.name ?: return "问答失败：runtime 未返回结果。"
        // 截取模型最后一次输出文本，仅保留非空内容
        val lastOutput = runState.lastModelOutput
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return if (lastOutput == null) {
            "问答失败：runtime 未返回结果（$failureReason）。"
        } else {
            "问答失败：runtime 未返回结果（$failureReason：$lastOutput）。"
        }
    }

    /**
     * 在 runtime 失败时构造一份本地规则兜底结果。
     *
     * 用问答上下文填充问题与模式字段，并使用统一生成的失败文案作为答案，
     * 同时附带 warning，方便上层在日志/审计中追溯失败原因。
     *
     * @param modeContext 问答模式上下文
     * @param result 原始 runtime 结果
     * @return 用作兜底展示的补丁结果
     */
    private fun buildQaRuntimeFailureResult(
        modeContext: QaModeContext,
        result: AgentRunResult<GraphPatchResult>,
    ): GraphPatchResult {
        val failureReason = result.finalState.failureReason?.name ?: "UNKNOWN"
        val lastOutput = result.finalState.lastModelOutput
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = modeContext.question,
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
            answer = qaRuntimeNullOutputMessage(result.finalState),
            promptPreview = "",
            warnings = listOf(
                buildString {
                    append("RUNTIME: runtime 未返回结果，failureReason=").append(failureReason)
                    if (lastOutput != null) {
                        append("，lastModelOutput=").append(lastOutput)
                    }
                    append("。")
                },
            ),
        )
    }
}
