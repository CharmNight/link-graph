package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.RiskResolutionSnapshot
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.llm.tools.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlanDiscussionService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.capability.CodegenCapability
import com.charmnight.linkgraph.llm.capability.PlanCapability
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.intellij.diff.merge.MergeRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 代码生成工作流依赖集合。
 *
 * 把生成流程所需的外部服务、运行时上下文、事件出口等聚合到一个数据结构中，
 * 便于工作流各步骤按需取用而无需关心具体装配方式。
 */
internal data class GenerationWorkflowDependencies(
    val project: Project,
    val snapshotProvider: EditorSnapshotProvider,
    val toolGraphSnapshotProvider: ToolGraphSnapshotProvider,
    val planningContextFactory: PlanningContextFactory,
    val graphGenerationService: GraphGenerationService,
    val codeGenerationService: CodeGenerationService,
    val codeDraftWriterService: CodeDraftWriterService,
    val sourceNavigationServiceProvider: () -> SourceNavigationService,
    val settingsProvider: () -> LinkGraphSettingsState,
    val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    val logger: Logger,
    val agentRunCoordinator: AgentRunCoordinator,
    val artifactStoreProvider: () -> ArtifactStore,
    val eventSink: GraphEditorApplicationEventSink,
    val planCapabilityFactory: (PlanCapability.PlanExecutor) -> PlanCapability,
    val codegenCapabilityFactory: (CodegenCapability.CodegenExecutor) -> CodegenCapability,
    val riskResolutionService: RiskResolutionService,
    val generationPlanDiscussionService: GenerationPlanDiscussionService,
    val showCodeDraftMergeRequest: (Project, MergeRequest) -> Unit,
)

/** 工作流前置条件检查未通过时的失败信息，用于中止流程并向 UI 反馈原因。 */
internal data class GenerationPrerequisiteFailure(
    val scene: String,
    val message: String,
    val detailMessage: String?,
)

/** 运行期失败结果，记录用户可见消息与可选的详细原因。 */
internal data class RuntimeFailureResult(
    val message: String,
    val detailMessage: String?,
)

/** 把规划阶段输入转换为生成上下文，作为后续 LLM 调用的统一入参来源。 */
internal fun GenerationWorkflowDependencies.buildGenerationContext(payload: PlanningInput): GenerationContext {
    return GenerationContext(
        graph = payload.planningGraph,
        mermaidIssues = payload.mermaidIssues,
        diff = payload.diff,
        syncPreviewItems = payload.previewItems,
        confirmedChanges = payload.confirmedChanges,
        sourceContext = payload.sourceContext,
        userGoal = payload.userGoal,
    )
}

/** 把代理运行结果中携带的产物摘要映射为应用层使用的运行时产物摘要列表。 */
internal fun GenerationWorkflowDependencies.toRuntimeArtifactSummaries(
    result: AgentRunResult<*>,
): List<ApplicationRuntimeArtifactSummary> {
    return result.artifactSummaries.map(ApplicationRuntimeArtifactSummary::from)
}

/** 通过事件出口派发一个应用事件。 */
internal fun GenerationWorkflowDependencies.emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

/** 派发生成过程的流式预览事件，把增量文本与是否收尾的状态推送到 UI。 */
internal fun GenerationWorkflowDependencies.emitGenerationStreamingPreview(
    scene: com.charmnight.linkgraph.application.result.GenerationRequestScene,
    requestId: Long,
    previewText: String,
    finalizingStructuredResult: Boolean,
) {
    emit(
        GraphEditorApplicationEvent.GenerationStreamingPreview(
            scene = scene,
            requestId = requestId,
            previewText = previewText,
            finalizingStructuredResult = finalizingStructuredResult,
        ),
    )
}

/** 派发一条生成流程反馈消息事件，可指定是否保留上一次的状态类型。 */
internal fun GenerationWorkflowDependencies.emitGenerationFeedback(
    level: ApplicationFeedbackLevel,
    message: String,
    preservePreviousStatusKind: Boolean = false,
) {
    emit(GraphEditorApplicationEvent.GenerationFeedback(level, message, preservePreviousStatusKind))
}

/** 派发合并写盘报告事件，附上反馈级别与消息便于 UI 提示用户。 */
internal fun GenerationWorkflowDependencies.emitMergeWriteReport(
    report: com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport,
    level: ApplicationFeedbackLevel,
    message: String,
) {
    emit(GraphEditorApplicationEvent.MergeWriteReported(report, level, message))
}

/** 基于当前风险解析快照重新评估草稿校验与代码准入，并派发更新事件，返回最新代码准入决策。 */
internal fun GenerationWorkflowDependencies.refreshDraftAndCodeState(
    snapshot: RiskResolutionSnapshot,
): StageEligibilityDecision {
    val draftValidationState = riskResolutionService.evaluateDraftValidation(snapshot)
    val codeDecision = riskResolutionService.evaluateCodeEligibility(snapshot)
    emit(GraphEditorApplicationEvent.DraftAndCodeEligibilityUpdated(draftValidationState, codeDecision))
    return codeDecision
}

/** 当阶段准入未通过时构造失败信息并派发代码草稿失败事件；通过准入则返回 null 继续执行流程。 */
internal fun GenerationWorkflowDependencies.rejectStageEligibility(
    decision: StageEligibilityDecision,
    scene: String,
): GenerationPrerequisiteFailure? {
    if (decision.allowed) {
        return null
    }
    val failure = GenerationPrerequisiteFailure(
        scene = scene,
        message = decision.message,
        detailMessage = decision.detailMessage,
    )
    emit(
        GraphEditorApplicationEvent.CodeDraftRequestFailed(
            GenerationRequestFailureResult(
            scene = failure.scene,
            message = failure.message,
            requestState = AsyncRequestState.failed(
                message = failure.message,
                scene = failure.scene,
                detailMessage = failure.detailMessage,
            ),
            feedbackLevel = ApplicationFeedbackLevel.WARNING,
        ),
        ),
    )
    return failure
}

/** 当快照中存在实现计划但找不到对应 PlanArtifact 时拒绝继续执行，避免脱离产物谱系继续生成代码草稿。 */
internal fun GenerationWorkflowDependencies.rejectOrphanedGenerationPlan(
    snapshot: WorkflowEditorSnapshot,
    scene: String,
): GenerationPrerequisiteFailure? {
    val snapshotPlan = snapshot.generationPlan?.let { rawPlan ->
        ProjectPathNormalizer.normalizePlan(rawPlan, project.basePath)
    } ?: return null
    val hasPlanArtifact = artifactStoreProvider()
        .byType(ArtifactType.PLAN)
        .filterIsInstance<PlanArtifact>()
        .any { artifact -> artifact.plan == snapshotPlan }
    if (hasPlanArtifact) {
        return null
    }
    val failure = GenerationPrerequisiteFailure(
        scene = scene,
        message = "生成${scene}前请先使用 runtime 重新生成实现计划，当前实现计划缺少对应的 PlanArtifact。",
        detailMessage = "当前 UI 中存在实现计划，但缺少对应的 PlanArtifact。重新生成实现计划后再继续代码草稿生成，避免脱离 artifact lineage 继续执行。",
    )
    emit(
        GraphEditorApplicationEvent.CodeDraftRequestFailed(
            GenerationRequestFailureResult(
            scene = failure.scene,
            message = failure.message,
            requestState = AsyncRequestState.failed(
                message = failure.message,
                scene = failure.scene,
                detailMessage = failure.detailMessage,
            ),
            feedbackLevel = ApplicationFeedbackLevel.WARNING,
        ),
        ),
    )
    return failure
}
