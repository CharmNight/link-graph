package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.port.QaCompletedPresentation
import com.charmnight.linkgraph.application.port.QaFailedPresentation
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

sealed interface ReviewUseCaseResult {
    data class QaCompleted(val presentation: QaCompletedPresentation) : ReviewUseCaseResult
    data class QaFailed(
        val presentation: QaFailedPresentation,
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
            val message = qaRuntimeNullOutputMessage()
            return ReviewUseCaseResult.QaFailed(
                presentation = QaFailedPresentation(
                    message = message,
                    requestState = requestState.copy(errorMessage = message),
                    failedRequest = modeContext.request,
                    runtimeArtifacts = runtimeArtifacts,
                ),
                fallbackResult = buildQaRuntimeFailureResult(modeContext, runtimeResult),
            )
        }
        return ReviewUseCaseResult.QaCompleted(
            QaCompletedPresentation(
                result = output,
                requestState = requestState.copy(promptPreviewAvailable = output.promptPreview.isNotBlank()),
                completedRequest = modeContext.request,
                draftValidationState = draftValidationState,
                codeEligibilityDecision = codeEligibilityDecision,
                runtimeArtifacts = runtimeArtifacts,
            ),
        )
    }

    private fun qaRuntimeNullOutputMessage(): String = "问答失败：runtime 未返回结果。"

    private fun buildQaRuntimeFailureResult(
        modeContext: QaModeContext,
        result: AgentRunResult<GraphPatchResult>,
    ): GraphPatchResult {
        val failureReason = result.finalState.failureReason?.name ?: "UNKNOWN"
        return GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = modeContext.question,
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
            answer = qaRuntimeNullOutputMessage(),
            promptPreview = "",
            warnings = listOf("runtime 未返回结果，failureReason=$failureReason。"),
        )
    }
}
