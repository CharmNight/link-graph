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

/**
 * 生成用例结果 sealed 接口：统一封装实现计划与代码草稿生成相关的成功/失败结果类型，
 * 便于上层做穷尽式分支处理。
 */
sealed interface GenerationUseCaseResult {
    /** 实现计划已就绪，携带面向前端展示的展示对象。 */
    data class PlanReady(val presentation: GenerationPlanResult) : GenerationUseCaseResult
    /** 代码草稿已就绪，携带面向前端展示的展示对象。 */
    data class CodeDraftsReady(val presentation: GeneratedCodeDraftsResult) : GenerationUseCaseResult
    /** 代码草稿生成失败，携带面向前端的失败展示对象（含原因与状态）。 */
    data class CodeDraftFailed(val presentation: GenerationRequestFailureResult) : GenerationUseCaseResult
}

/**
 * 生成用例：协调"实现计划"与"代码草稿"两类生成结果的应用层逻辑，
 * 把 runtime 执行结果、请求状态与运行时产物组装为可呈现给前端的结果对象，
 * 并处理 runtime 失败或空结果时的回退与友好提示。
 *
 * @param planSnapshotBuilder 由外部注入的快照构造函数：根据计划所需输入（图、差异、同步预览、Mermaid 问题、已确认变更、源上下文、用户目标）生成实现计划
 * @param projectBasePathProvider 项目根路径提供函数：用于在回退计划中规范化路径
 */
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
    /**
     * 解析实现计划 runtime 结果：优先采用 runtime 输出，缺失时回退到本地快照构造的计划。
     * 同时根据回退状态调整反馈级别（成功或告警）与状态文案。
     * @param payload 计划输入载荷（包含图、差异、预览项等）
     * @param runtimeResult runtime 执行结果
     * @param requestState 当前请求异步状态
     * @param runtimeArtifacts runtime 产生的产物摘要
     */
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

    /**
     * 解析代码草稿 runtime 结果：
     * - runtime 输出为空时，根据 runtime 终态推导失败原因并返回失败结果；
     * - 输出为空草稿列表时，给出空结果友好提示并返回失败结果；
     * - 否则返回成功的草稿展示对象，并合并外部预先生成的草稿（如有）。
     * @param runtimeResult runtime 执行结果
     * @param requestState 当前请求异步状态
     * @param runtimeArtifacts runtime 产生的产物摘要
     * @param preparedDrafts 外部预先生成的草稿（如有则优先使用，绕过 runtime 输出的草稿）
     */
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

    /**
     * 回退计划：在 runtime 未返回实现计划时，本地基于输入载荷直接构造一份计划，
     * 并加上"runtime 未返回结果"的告警提示，便于前端展示已发生回退。
     */
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

    /**
     * 根据 runtime 终态推导代码草稿生成失败的展示信息：
     * 先看最后一步摘要是否对应本地校验失败；否则按失败原因归类给出相应提示。
     * 同时尽量补充可读的明细信息（最后模型输出或失败原因）。
     */
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

    /** runtime 失败解析结果：主消息与可选的明细信息（用于在 UI 展示主因 + 调试明细）。 */
    private data class RuntimeFailureResult(
        val message: String,
        val detailMessage: String?,
    )

    /**
     * 合并两段明细信息：去除空白与重复，非空时按换行拼接，全部为空则返回 null。
     */
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
