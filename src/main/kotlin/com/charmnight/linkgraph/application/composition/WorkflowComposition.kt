package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.artifact.ConfirmedDraftArtifactWriter
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.application.workflow.ConfirmedDraftChangeCoordinator
import com.charmnight.linkgraph.application.workflow.DraftPatchWorkflow
import com.charmnight.linkgraph.application.workflow.GraphWorkspaceWorkflow
import com.charmnight.linkgraph.application.workflow.InvocationExpansionWorkflow
import com.charmnight.linkgraph.application.workflow.ProjectDebugWorkflow
import com.charmnight.linkgraph.application.workflow.ReviewWorkflow
import com.charmnight.linkgraph.application.workflow.SourceNavigationWorkflow
import com.charmnight.linkgraph.application.workflow.SubjectGraphWorkflow
import com.charmnight.linkgraph.application.workflow.WorkspaceChangeCoordinator
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureGraphWorkflow
import com.charmnight.linkgraph.application.workflow.architecture.ClassDiagramWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationWorkflowDependencies
import com.charmnight.linkgraph.application.workflow.ReviewGraphWorkflow
import com.charmnight.linkgraph.foundation.LoggedFailures
import com.charmnight.linkgraph.llm.GenerationPlanDiscussionService
import com.charmnight.linkgraph.llm.capability.CodegenCapability
import com.charmnight.linkgraph.llm.capability.PlanCapability
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MarkdownSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MyBatisXmlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.SqlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.XmlResourceSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.YamlPropertiesSemanticProvider
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.intellij.diff.DiffManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Assembles the workflow layer on top of [InfrastructureComposition]. Each workflow is constructed
 * lazily under [LazyThreadSafetyMode.PUBLICATION]; collaborators are pulled from the shared
 * infrastructure composition so cross-workflow wiring (e.g. reviewFlow reusing workspaceFlow's
 * edit-request executor) stays explicit and traceable.
 */
internal class WorkflowComposition(
    private val project: Project,
    private val logger: Logger,
    private val infrastructure: InfrastructureComposition,
    private val testOverrides: LinkGraphProjectTestOverrides,
) {
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CodeSubjectHandleFactory()
    }

    private val defaultSubjectLocator: SubjectLocator by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CaretSubjectLocator()
    }

    private val defaultSemanticAnalyzer: SemanticAnalyzer by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    CodeSemanticProvider(
                        architectureIndexProvider = {
                            LoggedFailures.orNull(logger, "CodeSemanticProvider architectureIndexSupport.currentIndex") {
                                infrastructure.architectureIndexSupport.currentIndex()
                            }
                        },
                    ),
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

    private val defaultAnalysisOutcomeFactory: AnalysisOutcomeFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AnalysisOutcomeFactory(
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    private val subjectLocator: SubjectLocator
        get() = testOverrides.subjectLocator ?: defaultSubjectLocator

    private val semanticAnalyzer: SemanticAnalyzer
        get() = testOverrides.semanticAnalyzer ?: defaultSemanticAnalyzer

    private val analysisOutcomeFactory: AnalysisOutcomeFactory
        get() = testOverrides.analysisOutcomeFactory ?: defaultAnalysisOutcomeFactory

    val subjectFlow: SubjectGraphWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SubjectGraphWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            asyncRequestLifecycle = infrastructure.asyncRequestLifecycle,
            subjectLocatorProvider = { subjectLocator },
            semanticAnalyzerProvider = { semanticAnalyzer },
            analysisOutcomeFactoryProvider = { analysisOutcomeFactory },
            codeSubjectHandleFactory = codeSubjectHandleFactory,
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            eventSink = infrastructure.eventSink,
            onInvalidateQaRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
            onLogGraphDiagnostics = infrastructure.graphDiagnosticsLogger::log,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
            logger = logger,
        )
    }

    val workspaceChangeCoordinator: WorkspaceChangeCoordinator by lazy(LazyThreadSafetyMode.PUBLICATION) {
        WorkspaceChangeCoordinator(
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            clearSubjectAnalysisCache = subjectFlow::clearLastAnalysisCache,
            invalidateAsyncRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
        )
    }

    val workspaceFlow: GraphWorkspaceWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GraphWorkspaceWorkflow(
            snapshotProvider = infrastructure.editorSnapshotProvider,
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            eventSink = infrastructure.eventSink,
            mermaidImporter = infrastructure.mermaidImporter,
            mermaidValidator = infrastructure.mermaidValidator,
            mermaidExporter = infrastructure.mermaidExporter,
            graphDiffer = infrastructure.graphDiffer,
            syncPreviewPlanner = infrastructure.syncPreviewPlanner,
            copyToClipboard = { exported ->
                runCatching {
                    CopyPasteManager.getInstance().setContents(StringSelection(exported))
                    true
                }.getOrDefault(false)
            },
        )
    }

    val draftPatchFlow: DraftPatchWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DraftPatchWorkflow(
            snapshotProvider = infrastructure.applicationSnapshotProvider,
            eventSink = infrastructure.eventSink,
            graphPatchApplyService = infrastructure.graphPatchApplyService,
        )
    }

    val generationDependencies: GenerationWorkflowDependencies by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationWorkflowDependencies(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            toolGraphSnapshotProvider = infrastructure.toolGraphSnapshotProvider,
            planningContextFactory = infrastructure.planningContextFactory,
            graphGenerationService = infrastructure.graphGenerationService,
            codeGenerationService = infrastructure.codeGenerationService,
            codeDraftWriterService = infrastructure.codeDraftWriterService,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            eventSink = infrastructure.eventSink,
            settingsProvider = infrastructure.runtimeSupport::effectiveGenerationSettings,
            asyncRequestLifecycle = infrastructure.asyncRequestLifecycle,
            logger = logger,
            artifactStoreProvider = { infrastructure.artifactStore },
            agentRunCoordinator = AgentRunCoordinator(),
            planCapabilityFactory = { planExecutor ->
                PlanCapability(planExecutor = planExecutor)
            },
            codegenCapabilityFactory = { codegenExecutor ->
                CodegenCapability(project = project, codegenExecutor = codegenExecutor)
            },
            riskResolutionService = infrastructure.riskResolutionService,
            generationPlanDiscussionService = GenerationPlanDiscussionService(),
            showCodeDraftMergeRequest = { currentProject, request ->
                DiffManager.getInstance().showMerge(currentProject, request)
            },
        )
    }

    val generationPlanFlow: GenerationPlanWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationPlanWorkflow(generationDependencies)
    }

    val generationDiscussionFlow: GenerationPlanDiscussionWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        GenerationPlanDiscussionWorkflow(generationDependencies)
    }

    val codeDraftGenerationFlow: CodeDraftGenerationWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CodeDraftGenerationWorkflow(generationDependencies)
    }

    val codeDraftApplyFlow: CodeDraftApplyWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        CodeDraftApplyWorkflow(generationDependencies)
    }

    val reviewFlow: ReviewWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReviewWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            toolGraphSnapshotProvider = infrastructure.toolGraphSnapshotProvider,
            eventSink = infrastructure.eventSink,
            planningContextFactory = infrastructure.planningContextFactory,
            graphQaPatchService = infrastructure.graphQaPatchService,
            graphDiffPatchService = infrastructure.graphDiffPatchService,
            graphBeautificationService = infrastructure.graphBeautificationService,
            graphDiffer = infrastructure.graphDiffer,
            settingsProvider = infrastructure.runtimeSupport::effectiveGenerationSettings,
            qaExecutorOverrideProvider = { testOverrides.qaExecutor },
            asyncRequestLifecycle = infrastructure.asyncRequestLifecycle,
            logger = logger,
            artifactStoreProvider = { infrastructure.artifactStore },
            graphEditRequestExecutor = workspaceFlow::handleGraphEditRequest,
        )
    }

    val sourceNavigationFlow: SourceNavigationWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SourceNavigationWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            eventSink = infrastructure.eventSink,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            navigationNodeFinder = ::findTrustedNavigationNodeFromIndex,
            showSettingsDialog = infrastructure.runtimeSupport::openSettingsDialog,
            logger = logger,
        )
    }

    val invocationExpansionFlow: InvocationExpansionWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        InvocationExpansionWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            workspaceGraphCommitter = infrastructure.workspaceGraphCommitter,
            eventSink = infrastructure.eventSink,
            semanticAnalyzerProvider = { semanticAnalyzer },
            analysisOutcomeFactoryProvider = { analysisOutcomeFactory },
            codeSubjectHandleFactory = codeSubjectHandleFactory,
            targetResolverOverrideProvider = { testOverrides.invocationExpansionTargetResolver },
            subjectResolverOverrideProvider = { testOverrides.invocationExpansionSubjectResolver },
            logger = logger,
        )
    }

    val architectureGraphFlow: ArchitectureGraphWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ArchitectureGraphWorkflow(
            project = project,
            indexSupport = infrastructure.architectureIndexSupport,
            eventSink = infrastructure.eventSink,
            logger = logger,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    val classDiagramFlow: ClassDiagramWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ClassDiagramWorkflow(
            project = project,
            indexSupport = infrastructure.architectureIndexSupport,
            eventSink = infrastructure.eventSink,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            logger = logger,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    val reviewGraphFlow: ReviewGraphWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReviewGraphWorkflow(
            project = project,
            snapshotProvider = infrastructure.editorSnapshotProvider,
            indexSupport = infrastructure.architectureIndexSupport,
            graphDiffer = infrastructure.graphDiffer,
            eventSink = infrastructure.eventSink,
            logger = logger,
            runtimeTrace = infrastructure.runtimeSupport.runtimeTraceSink(),
        )
    }

    val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ConfirmedDraftChangeCoordinator(
            snapshotProvider = infrastructure.applicationSnapshotProvider,
            eventSink = infrastructure.eventSink,
            draftWorkbenchService = infrastructure.draftWorkbenchService,
            graphPatchApplyService = infrastructure.graphPatchApplyService,
            graphDiagnosticsLogger = infrastructure.graphDiagnosticsLogger,
            artifactWriter = ConfirmedDraftArtifactWriter { infrastructure.artifactStore },
            invalidateQaRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
            logger = logger,
            runtimeTrace = infrastructure.runtimeSupport.eagerRuntimeTraceSink(),
        )
    }

    val debugFlow: ProjectDebugWorkflow by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ProjectDebugWorkflow(
            logger = logger,
            debugGraphFactory = infrastructure.debugGraphFactory,
            subjectGraphWorkflow = subjectFlow,
            invalidateQaRequests = infrastructure.asyncRequestLifecycle::invalidateRequests,
            eventSink = infrastructure.eventSink,
        )
    }
}

private fun findTrustedNavigationNodeFromIndex(
    snapshot: WorkflowEditorSnapshot,
    nodeId: String,
): GraphNode? = snapshot.trustedNavigationNodes[nodeId]
