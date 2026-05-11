package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.artifact.ConfirmedDraftArtifactWriter
import com.charmnight.linkgraph.application.model.GraphEditScript
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.application.port.GraphEditorPresentationProvider
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.DefaultGraphBeautificationService
import com.charmnight.linkgraph.llm.GraphQaPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.semantic.provider.resource.MarkdownSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MyBatisXmlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.SqlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.XmlResourceSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.YamlPropertiesSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.workflow.ConfirmedDraftChangeCoordinator
import com.charmnight.linkgraph.application.debug.DebugGraphFactory
import com.charmnight.linkgraph.application.workflow.DraftPatchWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationWorkflowDependencies
import com.charmnight.linkgraph.application.diagnostics.GraphDiagnosticsLogger
import com.charmnight.linkgraph.application.workflow.GraphWorkspaceWorkflow
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.workflow.ProjectDebugWorkflow
import com.charmnight.linkgraph.application.workflow.ReviewWorkflow
import com.charmnight.linkgraph.application.workflow.SourceNavigationWorkflow
import com.charmnight.linkgraph.application.workflow.SubjectGraphWorkflow
import com.charmnight.linkgraph.application.workflow.WorkspaceChangeCoordinator
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * Project-level composition root for Link Graph workflows and shared collaborators.
 *
 * This service owns object assembly only. Production entrypoints should depend on the focused command
 * services that wrap these workflows, not on the legacy project-service facade.
 */
@Service(Service.Level.PROJECT)
internal class GraphEditorApplicationService(
    private val project: Project,
) : Disposable {
    private val testOverrides: LinkGraphProjectTestOverrides
        get() = project.getService(LinkGraphProjectTestOverrides::class.java)

    private val codeSubjectHandleFactory: CodeSubjectHandleFactory by lazy(LazyThreadSafetyMode.NONE) {
        CodeSubjectHandleFactory()
    }

    private val defaultSubjectLocator: SubjectLocator by lazy(LazyThreadSafetyMode.NONE) {
        CaretSubjectLocator()
    }

    private val defaultSemanticAnalyzer: SemanticAnalyzer by lazy(LazyThreadSafetyMode.NONE) {
        SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    CodeSemanticProvider(),
                    MyBatisXmlSemanticProvider(),
                    XmlResourceSemanticProvider(),
                    YamlPropertiesSemanticProvider(),
                    MarkdownSemanticProvider(),
                    SqlSemanticProvider(),
                ),
            ),
            project = project,
        )
    }

    private val runtimeSupport by lazy(LazyThreadSafetyMode.NONE) {
        LinkGraphProjectRuntimeSupport(
            project = project,
            logger = logger,
            openSettingsOverrideProvider = { testOverrides.openSettings },
            effectiveGenerationSettingsOverrideProvider = { testOverrides.effectiveGenerationSettings },
        )
    }

    private val defaultAnalysisOutcomeFactory: AnalysisOutcomeFactory by lazy(LazyThreadSafetyMode.NONE) {
        AnalysisOutcomeFactory(
            runtimeTrace = runtimeSupport.runtimeTraceSink(),
        )
    }

    private val subjectLocator: SubjectLocator
        get() = testOverrides.subjectLocator ?: defaultSubjectLocator

    private val semanticAnalyzer: SemanticAnalyzer
        get() = testOverrides.semanticAnalyzer ?: defaultSemanticAnalyzer

    private val analysisOutcomeFactory: AnalysisOutcomeFactory
        get() = testOverrides.analysisOutcomeFactory ?: defaultAnalysisOutcomeFactory

    private val mermaidImporter by lazy { MermaidImporter() }
    private val mermaidValidator by lazy { MermaidValidator() }
    private val mermaidExporter by lazy { MermaidExporter() }
    private val graphDiffer by lazy { GraphDiffer() }
    private val syncPreviewPlanner by lazy { SyncPreviewPlanner() }
    private val graphPatchApplyService by lazy { GraphPatchApplyService() }
    private val graphGenerationService by lazy { GraphGenerationService() }
    private val graphQaPatchService by lazy { GraphQaPatchService() }
    private val draftWorkbenchService by lazy { DraftWorkbenchService() }
    private val riskResolutionService by lazy { RiskResolutionService() }
    private val graphDiffPatchService by lazy { GraphDiffPatchService() }
    private val graphBeautificationService: GraphBeautificationService by lazy { DefaultGraphBeautificationService() }
    private val codeGenerationService by lazy { CodeGenerationService() }
    private val codeDraftWriterService by lazy { CodeDraftWriterService(project) }
    private val graphDiagnosticsLogger by lazy { GraphDiagnosticsLogger(logger) }
    private val debugGraphFactory by lazy { DebugGraphFactory() }

    private val presentationProvider by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(GraphEditorPresentationProvider::class.java)
    }

    private val editorSnapshotProvider by lazy(LazyThreadSafetyMode.NONE) {
        presentationProvider.editorSnapshotProvider()
    }

    private val applicationSnapshotProvider by lazy(LazyThreadSafetyMode.NONE) {
        presentationProvider.applicationSnapshotProvider()
    }

    private val toolGraphSnapshotProvider by lazy(LazyThreadSafetyMode.NONE) {
        presentationProvider.toolGraphSnapshotProvider()
    }

    private val workspaceGraphCommitter by lazy(LazyThreadSafetyMode.NONE) {
        presentationProvider.workspaceGraphCommitter()
    }

    private val artifactStore by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    }

    private val asyncRequestLifecycle by lazy(LazyThreadSafetyMode.NONE) {
        AsyncRequestLifecycleSupport(
            project = project,
            timeoutOverrideProvider = { testOverrides.asyncRequestTimeoutMillis },
        )
    }

    private val planningContextFactory by lazy(LazyThreadSafetyMode.NONE) {
        PlanningContextFactory(
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            graphGenerationService = graphGenerationService,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            projectBasePathProvider = { project.basePath },
        )
    }

    private val subjectFlow: SubjectGraphWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        SubjectGraphWorkflow(
            project = project,
            snapshotProvider = editorSnapshotProvider,
            asyncRequestLifecycle = asyncRequestLifecycle,
            subjectLocatorProvider = { subjectLocator },
            semanticAnalyzerProvider = { semanticAnalyzer },
            analysisOutcomeFactoryProvider = { analysisOutcomeFactory },
            codeSubjectHandleFactory = codeSubjectHandleFactory,
            workspaceGraphCommitter = workspaceGraphCommitter,
            eventSink = presentationProvider.eventSink(),
            onInvalidateQaRequests = asyncRequestLifecycle::invalidateRequests,
            onLogGraphDiagnostics = graphDiagnosticsLogger::log,
            runtimeTrace = runtimeSupport.runtimeTraceSink(),
            logger = logger,
        )
    }

    private val workspaceChangeCoordinator: WorkspaceChangeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        WorkspaceChangeCoordinator(
            workspaceGraphCommitter = workspaceGraphCommitter,
            clearSubjectAnalysisCache = subjectFlow::clearLastAnalysisCache,
            invalidateAsyncRequests = asyncRequestLifecycle::invalidateRequests,
        )
    }

    private val workspaceFlow: GraphWorkspaceWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        GraphWorkspaceWorkflow(
            snapshotProvider = editorSnapshotProvider,
            workspaceGraphCommitter = workspaceGraphCommitter,
            eventSink = presentationProvider.eventSink(),
            mermaidImporter = mermaidImporter,
            mermaidValidator = mermaidValidator,
            mermaidExporter = mermaidExporter,
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            copyToClipboard = { exported ->
                runCatching {
                    CopyPasteManager.getInstance().setContents(StringSelection(exported))
                    true
                }.getOrDefault(false)
            },
        )
    }

    private val draftPatchFlow: DraftPatchWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        DraftPatchWorkflow(
            snapshotProvider = applicationSnapshotProvider,
            eventSink = presentationProvider.eventSink(),
            graphPatchApplyService = graphPatchApplyService,
        )
    }

    private val generationDependencies: GenerationWorkflowDependencies by lazy(LazyThreadSafetyMode.NONE) {
        GenerationWorkflowDependencies(
            project = project,
            snapshotProvider = editorSnapshotProvider,
            toolGraphSnapshotProvider = toolGraphSnapshotProvider,
            planningContextFactory = planningContextFactory,
            graphGenerationService = graphGenerationService,
            codeGenerationService = codeGenerationService,
            codeDraftWriterService = codeDraftWriterService,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            eventSink = presentationProvider.eventSink(),
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            asyncRequestLifecycle = asyncRequestLifecycle,
            logger = logger,
            artifactStoreProvider = { artifactStore },
            agentRunCoordinator = com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator(),
            planCapabilityFactory = { planExecutor ->
                com.charmnight.linkgraph.llm.capability.PlanCapability(planExecutor = planExecutor)
            },
            codegenCapabilityFactory = { codegenExecutor ->
                com.charmnight.linkgraph.llm.capability.CodegenCapability(project = project, codegenExecutor = codegenExecutor)
            },
            riskResolutionService = riskResolutionService,
            generationPlanDiscussionService = com.charmnight.linkgraph.llm.GenerationPlanDiscussionService(),
            showCodeDraftMergeRequest = { currentProject, request ->
                com.intellij.diff.DiffManager.getInstance().showMerge(currentProject, request)
            },
        )
    }

    private val generationPlanFlow: GenerationPlanWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        GenerationPlanWorkflow(generationDependencies)
    }

    private val generationDiscussionFlow: GenerationPlanDiscussionWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        GenerationPlanDiscussionWorkflow(generationDependencies)
    }

    private val codeDraftGenerationFlow: CodeDraftGenerationWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        CodeDraftGenerationWorkflow(generationDependencies)
    }

    private val codeDraftApplyFlow: CodeDraftApplyWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        CodeDraftApplyWorkflow(generationDependencies)
    }

    private val reviewFlow: ReviewWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        ReviewWorkflow(
            project = project,
            snapshotProvider = editorSnapshotProvider,
            toolGraphSnapshotProvider = toolGraphSnapshotProvider,
            eventSink = presentationProvider.eventSink(),
            planningContextFactory = planningContextFactory,
            graphQaPatchService = graphQaPatchService,
            graphDiffPatchService = graphDiffPatchService,
            graphBeautificationService = graphBeautificationService,
            graphDiffer = graphDiffer,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            qaExecutorOverrideProvider = { testOverrides.qaExecutor },
            asyncRequestLifecycle = asyncRequestLifecycle,
            logger = logger,
            artifactStoreProvider = { artifactStore },
        )
    }

    private val sourceNavigationFlow: SourceNavigationWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        SourceNavigationWorkflow(
            project = project,
            snapshotProvider = editorSnapshotProvider,
            eventSink = presentationProvider.eventSink(),
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            navigationNodeFinder = ::findTrustedNavigationNodeFromIndex,
            showSettingsDialog = runtimeSupport::openSettingsDialog,
            logger = logger,
        )
    }

    private val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ConfirmedDraftChangeCoordinator(
            snapshotProvider = applicationSnapshotProvider,
            eventSink = presentationProvider.eventSink(),
            draftWorkbenchService = draftWorkbenchService,
            graphPatchApplyService = graphPatchApplyService,
            graphDiagnosticsLogger = graphDiagnosticsLogger,
            artifactWriter = ConfirmedDraftArtifactWriter { artifactStore },
            invalidateQaRequests = asyncRequestLifecycle::invalidateRequests,
            logger = logger,
            runtimeTrace = runtimeSupport.eagerRuntimeTraceSink(),
        )
    }

    private val debugFlow: ProjectDebugWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        ProjectDebugWorkflow(
            logger = logger,
            debugGraphFactory = debugGraphFactory,
            subjectGraphWorkflow = subjectFlow,
            invalidateQaRequests = asyncRequestLifecycle::invalidateRequests,
            eventSink = presentationProvider.eventSink(),
        )
    }

    fun previewCurrentEditorSubjectKind(editor: Editor? = null): SubjectPreviewKind? =
        subjectFlow.previewCurrentEditorSubjectKind(editor)

    fun addCurrentEditorContextNode(): Boolean =
        subjectFlow.addCurrentEditorContextNode()

    fun loadCurrentEditorContextGraphAsync(editor: Editor? = null) =
        subjectFlow.loadCurrentEditorContextGraphAsync(editor)

    fun requestExpandOverflowNode(nodeId: String) =
        subjectFlow.requestExpandOverflowNode(nodeId)

    fun requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) =
        subjectFlow.requestAnalysisDisplayMode(displayMode)

    fun loadGraph(graph: GraphDocument, source: String) {
        workspaceChangeCoordinator.resetWorkspaceGraphContext()
        workspaceFlow.loadGraph(graph, source)
    }

    fun importMermaid(mermaid: String): GraphDocument {
        workspaceChangeCoordinator.resetWorkspaceGraphContext()
        return workspaceFlow.importMermaid(mermaid)
    }

    fun exportMermaid(): String = workspaceFlow.exportMermaid()

    fun showDiffMode() =
        workspaceChangeCoordinator.invalidateRequests().let { workspaceFlow.showDiffMode() }

    fun handleFrontendEditScript(script: GraphEditScript) {
        workspaceChangeCoordinator.resetWorkspaceGraphContext()
        workspaceFlow.handleFrontendEditScript(script)
    }

    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) =
        workspaceFlow.handleFrontendLayoutChanged(positions)

    fun updateWorkbenchSectionPreference(preferences: Map<String, Boolean>) {
        presentationProvider.eventSink().emit(
            com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent.WorkbenchSectionPreferencesChanged(preferences),
        )
    }

    fun requestSyncPreview(): List<SyncPreviewItem> = workspaceFlow.requestSyncPreview()

    fun requestSourceNavigation(nodeId: String) =
        sourceNavigationFlow.requestSourceNavigation(nodeId)

    fun openSettings() = sourceNavigationFlow.openSettings()

    fun requestQaAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
    ) = reviewFlow.requestQaAsync(question, selectedNodeIds, sourceThreadId, mode)

    fun retryLastQaRequestAsync() = reviewFlow.retryLastQaRequestAsync()

    fun resolveInvestigationThread(
        threadId: String,
        status: com.charmnight.linkgraph.workbench.RiskResolutionStatus,
        note: String? = null,
    ) = reviewFlow.resolveInvestigationThread(threadId, status, note.orEmpty())

    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) = reviewFlow.requestDiffReviewAsync(question, selectedDiffItemIds)

    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ) = reviewFlow.requestGraphBeautificationAsync(goal, preferredStyle, explanationFocus, followUp, granularity)

    fun confirmQaCandidateChange(changeId: String) = confirmedDraftCoordinator.confirm(changeId)

    fun unconfirmQaCandidateChange(changeId: String) = confirmedDraftCoordinator.unconfirm(changeId)

    fun applyDraftPatchPreview(operationIds: Set<String>? = null) =
        draftPatchFlow.applyDraftPatchPreview(operationIds)

    fun clearDraftPatchPreview() = draftPatchFlow.clearDraftPatchPreview()

    fun restoreDraftPatchPreview(source: com.charmnight.linkgraph.application.model.DraftPatchPreviewSource) =
        draftPatchFlow.restoreDraftPatchPreview(source)

    fun undoLastDraftPatchApply() = draftPatchFlow.undoLastDraftPatchApply()

    fun requestGenerationPlanAsync() = generationPlanFlow.requestGenerationPlanAsync()

    fun requestGenerationPlanDiscussionAsync(question: String, focusItemId: String? = null) =
        generationDiscussionFlow.requestGenerationPlanDiscussionAsync(question, focusItemId)

    fun requestCodeDraftsAsync() = codeDraftGenerationFlow.requestCodeDraftsAsync()

    fun applyCodeDrafts() = codeDraftApplyFlow.applyCodeDrafts()

    fun applySingleCodeDraft(draftId: String) = codeDraftApplyFlow.applySingleCodeDraft(draftId)

    fun openCodeDraftNativeDiff(draftId: String) {
        testOverrides.openCodeDraftNativeDiff?.invoke(draftId)
            ?: codeDraftApplyFlow.openCodeDraftNativeDiff(draftId)
    }

    fun requestDraftNavigation(targetPath: String) = codeDraftApplyFlow.requestDraftNavigation(targetPath)

    fun prepareDebugRequestedAnalysisDisplayModeIfPresent(envName: String) =
        debugFlow.prepareDebugRequestedAnalysisDisplayModeIfPresent(envName)

    fun loadDebugMethodGraphBySignatureAsync(signature: String) =
        debugFlow.loadDebugMethodGraphBySignatureAsync(signature)

    fun loadDebugGraph(mode: String) = debugFlow.loadDebugGraph(mode)

    override fun dispose() {
        subjectFlow.dispose()
    }

    companion object {
        const val DEBUG_ANALYSIS_DISPLAY_MODE_ENV: String = "LINKGRAPH_DEBUG_ANALYSIS_DISPLAY_MODE"
        private val logger = Logger.getInstance(GraphEditorApplicationService::class.java)
    }
}

private fun findTrustedNavigationNodeFromIndex(
    snapshot: WorkflowEditorSnapshot,
    nodeId: String,
): GraphNode? = snapshot.trustedNavigationNodes[nodeId]
