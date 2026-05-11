package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.port.AuditCompletedPresentation
import com.charmnight.linkgraph.application.port.AuditFailedPresentation
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

sealed interface ReviewUseCaseResult {
    data class AuditCompleted(val presentation: AuditCompletedPresentation) : ReviewUseCaseResult
    data class AuditFailed(
        val presentation: AuditFailedPresentation,
        val fallbackResult: GraphPatchResult,
    ) : ReviewUseCaseResult
}

internal class ReviewUseCase(
    private val normalizeAuditResult: (GraphPatchResult, QaModeContext) -> GraphPatchResult,
) {
    fun resolveAuditRuntimeResult(
        runtimeResult: AgentRunResult<GraphPatchResult>,
        modeContext: QaModeContext,
        requestState: AsyncRequestState,
        runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
        draftValidationState: DraftValidationState?,
        codeEligibilityDecision: StageEligibilityDecision?,
    ): ReviewUseCaseResult {
        val output = runtimeResult.output?.let { normalizeAuditResult(it, modeContext) }
        if (output == null) {
            val message = auditRuntimeNullOutputMessage()
            return ReviewUseCaseResult.AuditFailed(
                presentation = AuditFailedPresentation(
                    message = message,
                    requestState = requestState.copy(errorMessage = message),
                    failedRequest = modeContext.request,
                    runtimeArtifacts = runtimeArtifacts,
                ),
                fallbackResult = buildAuditRuntimeFailureResult(modeContext, runtimeResult),
            )
        }
        return ReviewUseCaseResult.AuditCompleted(
            AuditCompletedPresentation(
                result = output,
                requestState = requestState.copy(promptPreviewAvailable = output.promptPreview.isNotBlank()),
                completedRequest = modeContext.request,
                draftValidationState = draftValidationState,
                codeEligibilityDecision = codeEligibilityDecision,
                runtimeArtifacts = runtimeArtifacts,
            ),
        )
    }

    private fun auditRuntimeNullOutputMessage(): String = "问答失败：runtime 未返回结果。"

    private fun buildAuditRuntimeFailureResult(
        modeContext: QaModeContext,
        result: AgentRunResult<GraphPatchResult>,
    ): GraphPatchResult {
        val failureReason = result.finalState.failureReason?.name ?: "UNKNOWN"
        return GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = modeContext.question,
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
            answer = auditRuntimeNullOutputMessage(),
            promptPreview = "",
            warnings = listOf("runtime 未返回结果，failureReason=$failureReason。"),
        )
    }
}
