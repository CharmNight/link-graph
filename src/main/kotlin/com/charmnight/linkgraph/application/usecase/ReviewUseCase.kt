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

sealed interface ReviewUseCaseResult {
    data class QaCompleted(val presentation: QaCompletedResult) : ReviewUseCaseResult
    data class QaFailed(
        val presentation: QaFailedResult,
        val fallbackResult: GraphPatchResult,
    ) : ReviewUseCaseResult
}

internal class ReviewUseCase(
    private val normalizeQaResult: (GraphPatchResult, QaModeContext) -> GraphPatchResult,
) {
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

    private fun qaRuntimeNullOutputMessage(runState: AgentRunState): String {
        val failureReason = runState.failureReason?.name ?: return "问答失败：runtime 未返回结果。"
        val lastOutput = runState.lastModelOutput
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return if (lastOutput == null) {
            "问答失败：runtime 未返回结果（$failureReason）。"
        } else {
            "问答失败：runtime 未返回结果（$failureReason：$lastOutput）。"
        }
    }

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
                    append("runtime 未返回结果，failureReason=").append(failureReason)
                    if (lastOutput != null) {
                        append("，lastModelOutput=").append(lastOutput)
                    }
                    append("。")
                },
            ),
        )
    }
}
