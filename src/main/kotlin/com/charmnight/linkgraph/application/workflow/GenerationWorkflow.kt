package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.llm.GenerationPlanDiscussionService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.capability.CodegenCapability
import com.charmnight.linkgraph.llm.capability.PlanCapability
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationWorkflowDependencies
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.diff.DiffManager
import com.intellij.diff.merge.MergeRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Facade for implementation plans, plan discussion, generated code drafts, and draft application.
 */
internal class GenerationWorkflow(
    project: Project,
    snapshotProvider: EditorSnapshotProvider,
    toolGraphSnapshotProvider: ToolGraphSnapshotProvider,
    eventSink: GraphEditorApplicationEventSink,
    planningContextFactory: PlanningContextFactory,
    graphGenerationService: GraphGenerationService,
    codeGenerationService: CodeGenerationService,
    codeDraftWriterService: CodeDraftWriterService,
    sourceNavigationServiceProvider: () -> SourceNavigationService,
    settingsProvider: () -> LinkGraphSettingsState,
    asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    logger: Logger,
    agentRunCoordinator: AgentRunCoordinator = AgentRunCoordinator(),
    artifactStoreProvider: () -> ArtifactStore = {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    },
    planCapabilityFactory: (PlanCapability.PlanExecutor) -> PlanCapability = { planExecutor ->
        PlanCapability(planExecutor = planExecutor)
    },
    codegenCapabilityFactory: (CodegenCapability.CodegenExecutor) -> CodegenCapability = { codegenExecutor ->
        CodegenCapability(project = project, codegenExecutor = codegenExecutor)
    },
    riskResolutionService: RiskResolutionService = RiskResolutionService(),
    generationPlanDiscussionService: GenerationPlanDiscussionService = GenerationPlanDiscussionService(),
    showCodeDraftMergeRequest: (Project, MergeRequest) -> Unit = { currentProject, request ->
        DiffManager.getInstance().showMerge(currentProject, request)
    },
) {
    private val dependencies = GenerationWorkflowDependencies(
        project = project,
        snapshotProvider = snapshotProvider,
        toolGraphSnapshotProvider = toolGraphSnapshotProvider,
        planningContextFactory = planningContextFactory,
        graphGenerationService = graphGenerationService,
        codeGenerationService = codeGenerationService,
        codeDraftWriterService = codeDraftWriterService,
        sourceNavigationServiceProvider = sourceNavigationServiceProvider,
        eventSink = eventSink,
        settingsProvider = settingsProvider,
        asyncRequestLifecycle = asyncRequestLifecycle,
        logger = logger,
        agentRunCoordinator = agentRunCoordinator,
        artifactStoreProvider = artifactStoreProvider,
        planCapabilityFactory = planCapabilityFactory,
        codegenCapabilityFactory = codegenCapabilityFactory,
        riskResolutionService = riskResolutionService,
        generationPlanDiscussionService = generationPlanDiscussionService,
        showCodeDraftMergeRequest = showCodeDraftMergeRequest,
    )
    private val planWorkflow = GenerationPlanWorkflow(dependencies)
    private val discussionWorkflow = GenerationPlanDiscussionWorkflow(dependencies)
    private val codeDraftWorkflow = CodeDraftGenerationWorkflow(dependencies)
    private val applyWorkflow = CodeDraftApplyWorkflow(dependencies)

    fun requestGenerationPlan() = planWorkflow.requestGenerationPlan()

    fun requestGenerationPlanAsync() = planWorkflow.requestGenerationPlanAsync()

    fun requestGenerationPlanDiscussion(
        question: String,
        focusItemId: String? = null,
    ) = discussionWorkflow.requestGenerationPlanDiscussion(question, focusItemId)

    fun requestGenerationPlanDiscussionAsync(
        question: String,
        focusItemId: String? = null,
    ) = discussionWorkflow.requestGenerationPlanDiscussionAsync(question, focusItemId)

    fun requestCodeDrafts() = codeDraftWorkflow.requestCodeDrafts()

    fun requestCodeDraftsAsync() = codeDraftWorkflow.requestCodeDraftsAsync()

    fun applyCodeDrafts() = applyWorkflow.applyCodeDrafts()

    fun applySingleCodeDraft(draftId: String) = applyWorkflow.applySingleCodeDraft(draftId)

    fun openCodeDraftNativeDiff(draftId: String) = applyWorkflow.openCodeDraftNativeDiff(draftId)

    fun requestDraftNavigation(targetPath: String) = applyWorkflow.requestDraftNavigation(targetPath)
}
