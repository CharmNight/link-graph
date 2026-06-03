package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.GeneratedCodeDraftsResult
import com.charmnight.linkgraph.application.result.GenerationPlanResult
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.codegen.emptyResultDetailMessage
import com.charmnight.linkgraph.codegen.emptyResultMessage
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRunState

sealed interface GenerationUseCaseResult {
    data class PlanReady(val presentation: GenerationPlanResult) : GenerationUseCaseResult
    data class CodeDraftsReady(val presentation: GeneratedCodeDraftsResult) : GenerationUseCaseResult
    data class CodeDraftFailed(val presentation: GenerationRequestFailureResult) : GenerationUseCaseResult
}

class GenerationUseCase(
    private val planSnapshotBuilder: (
        planningGraph: com.charmnight.linkgraph.model.GraphDocument,
        diff: com.charmnight.linkgraph.model.GraphDiff,
        previewItems: List<com.charmnight.linkgraph.sync.SyncPreviewItem>,
        mermaidIssues: List<com.charmnight.linkgraph.mermaid.MermaidIssue>,
        confirmedChanges: List<com.charmnight.linkgraph.workbench.DraftWorkbenchEntry>,
        sourceContext: List<com.charmnight.linkgraph.llm.SourceSnippetContext>,
        userGoal: String,
    ) -> GenerationPlan,
    private val projectBasePathProvider: () -> String?,
) {
    fun resolvePlan(
        payload: PlanningInput,
        runtimeResult: AgentRunResult<GenerationPlan>,
        requestState: AsyncRequestState,
        runtimeArtifacts: List<com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary>,
    ): GenerationUseCaseResult.PlanReady {
        val plan = runtimeResult.output ?: fallbackPlan(payload)
        val completedRequestState = requestState.copy(promptPreviewAvailable = plan.promptPreview.isNotBlank())
        return GenerationUseCaseResult.PlanReady(
            GenerationPlanResult(
                plan = plan,
                requestState = completedRequestState,
                runtimeArtifacts = runtimeArtifacts,
                feedbackLevel = if (requestState.fallbackUsed) ApplicationFeedbackLevel.WARNING else ApplicationFeedbackLevel.SUCCESS,
                statusMessage = requestState.statusMessage ?: "实现计划已生成。",
            ),
        )
    }

    fun resolveCodeDrafts(
        runtimeResult: AgentRunResult<CodeGenerationResult>,
        requestState: AsyncRequestState,
        runtimeArtifacts: List<com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary>,
        preparedDrafts: List<GeneratedCodeDraft>? = null,
    ): GenerationUseCaseResult {
        val draftResult = runtimeResult.output
        if (draftResult == null) {
            val failure = resolveCodegenRuntimeFailure(runtimeResult.finalState)
            return GenerationUseCaseResult.CodeDraftFailed(
                GenerationRequestFailureResult(
                    scene = "代码草稿",
                    message = failure.message,
                    requestState = requestState.copy(
                        errorMessage = failure.message,
                        detailMessage = mergedDetailMessage(failure.detailMessage, requestState.detailMessage),
                    ),
                    runtimeArtifacts = runtimeArtifacts,
                ),
            )
        }
        if (draftResult.drafts.isEmpty()) {
            val message = draftResult.emptyResultMessage()
            return GenerationUseCaseResult.CodeDraftFailed(
                GenerationRequestFailureResult(
                    scene = "代码草稿",
                    message = message,
                    requestState = requestState.copy(
                        errorMessage = message,
                        detailMessage = mergedDetailMessage(draftResult.emptyResultDetailMessage(), requestState.detailMessage),
                    ),
                    runtimeArtifacts = runtimeArtifacts,
                ),
            )
        }
        val completedRequestState = requestState.copy(promptPreviewAvailable = !draftResult.promptPreview.isNullOrBlank())
        return GenerationUseCaseResult.CodeDraftsReady(
            GeneratedCodeDraftsResult(
                drafts = preparedDrafts ?: draftResult.drafts,
                warnings = draftResult.warnings,
                source = draftResult.source,
                promptPreview = draftResult.promptPreview,
                requestState = completedRequestState,
                runtimeArtifacts = runtimeArtifacts,
                feedbackLevel = if (requestState.fallbackUsed) ApplicationFeedbackLevel.WARNING else ApplicationFeedbackLevel.SUCCESS,
                statusMessage = completedRequestState.statusMessage ?: "代码草稿已生成。",
            ),
        )
    }

    private fun fallbackPlan(payload: PlanningInput): GenerationPlan {
        val fallbackPlan = ProjectPathNormalizer.normalizePlan(
            planSnapshotBuilder(
                payload.planningGraph,
                payload.diff,
                payload.previewItems,
                payload.mermaidIssues,
                payload.confirmedChanges,
                payload.sourceContext,
                payload.userGoal,
            ),
            projectBasePathProvider(),
        )
        return fallbackPlan.copy(
            warnings = listOf(
                "实现计划 runtime 未返回结果，已直接基于当前草稿快照生成实现建议。",
            ) + fallbackPlan.warnings,
        )
    }

    private fun resolveCodegenRuntimeFailure(runtimeState: AgentRunState): RuntimeFailureResult {
        val lastStepSummary = runtimeState.stepRecords.lastOrNull()?.summary
        val message = when (lastStepSummary) {
            "validate-generated-drafts" ->
                "生成代码草稿失败：生成结果未通过本地安全校验。"
            "prevalidate-existing-file-targets" ->
                "生成代码草稿失败：目标范围未通过本地安全校验。"
            else -> when (runtimeState.failureReason) {
                AgentRunFailureReason.EVIDENCE_INSUFFICIENT ->
                    "生成代码草稿失败：当前证据不足以形成安全代码 diff。"
                AgentRunFailureReason.MAX_STEPS_EXCEEDED,
                AgentRunFailureReason.MAX_FILES_READ_EXCEEDED,
                AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED,
                AgentRunFailureReason.MAX_SNIPPET_LINES_EXCEEDED,
                AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED,
                AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED ->
                    "生成代码草稿失败：runtime 预算已耗尽。"
                else -> "生成代码草稿失败：runtime 执行失败。"
            }
        }
        val failureReasonDetail = runtimeState.failureReason?.let { reason -> "failureReason=${reason.name}" }
        val detailMessage = runtimeState.lastModelOutput
            ?.trim()
            ?.takeIf { detail -> detail.isNotEmpty() && detail != message }
            ?: failureReasonDetail
        return RuntimeFailureResult(message, detailMessage)
    }

    private data class RuntimeFailureResult(
        val message: String,
        val detailMessage: String?,
    )

    private fun mergedDetailMessage(
        primary: String?,
        secondary: String?,
    ): String? {
        return listOfNotNull(
            primary?.trim()?.takeIf(String::isNotEmpty),
            secondary?.trim()?.takeIf(String::isNotEmpty),
        ).distinct().joinToString("\n").takeIf(String::isNotEmpty)
    }
}
