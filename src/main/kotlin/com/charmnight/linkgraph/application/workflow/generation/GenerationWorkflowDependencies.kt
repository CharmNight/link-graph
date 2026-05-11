package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.RiskResolutionSnapshot
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
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
import com.charmnight.linkgraph.application.port.GenerationRequestFailurePresentation
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.intellij.diff.merge.MergeRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

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

internal data class GenerationPrerequisiteFailure(
    val scene: String,
    val message: String,
    val detailMessage: String?,
)

internal data class RuntimeFailurePresentation(
    val message: String,
    val detailMessage: String?,
)

internal fun GenerationWorkflowDependencies.buildGenerationContext(payload: PlanningInput): GenerationContext {
    return GenerationContext(
        graph = payload.planningGraph,
        mermaidIssues = payload.mermaidIssues,
        diff = payload.diff,
        syncPreviewItems = payload.previewItems,
        confirmedChanges = payload.confirmedChanges,
        sourceContext = payload.sourceContext,
    )
}

internal fun GenerationWorkflowDependencies.toRuntimeArtifactSummaries(
    result: AgentRunResult<*>,
): List<ApplicationRuntimeArtifactSummary> {
    return result.artifactSummaries.map(ApplicationRuntimeArtifactSummary::from)
}

internal fun GenerationWorkflowDependencies.emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

internal fun GenerationWorkflowDependencies.emitGenerationStreamingPreview(
    scene: com.charmnight.linkgraph.application.port.GenerationRequestScene,
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

internal fun GenerationWorkflowDependencies.emitGenerationFeedback(
    level: ApplicationFeedbackLevel,
    message: String,
    preserveLastMessageType: Boolean = false,
) {
    emit(GraphEditorApplicationEvent.GenerationFeedback(level, message, preserveLastMessageType))
}

internal fun GenerationWorkflowDependencies.emitMergeWriteReport(
    report: com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport,
    level: ApplicationFeedbackLevel,
    message: String,
) {
    emit(GraphEditorApplicationEvent.MergeWriteReported(report, level, message))
}

internal fun GenerationWorkflowDependencies.refreshDraftAndCodeState(
    snapshot: RiskResolutionSnapshot,
): StageEligibilityDecision {
    val draftValidationState = riskResolutionService.evaluateDraftValidation(snapshot)
    val codeDecision = riskResolutionService.evaluateCodeEligibility(snapshot)
    emit(GraphEditorApplicationEvent.DraftAndCodeEligibilityUpdated(draftValidationState, codeDecision))
    return codeDecision
}

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
            GenerationRequestFailurePresentation(
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
            GenerationRequestFailurePresentation(
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
