package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.diagnostics.GraphDiagnosticsLogger
import com.charmnight.linkgraph.application.debug.DebugGraphFactory
import com.charmnight.linkgraph.application.port.GraphEditorPresentationProvider
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeSupport
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureIndexWorkflowSupport
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.DefaultGraphBeautificationService
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.GraphQaPatchService
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Owns construction of project-wide collaborators that are shared across workflows:
 * runtime helpers, presentation ports, async lifecycle, planning, mermaid/diff/sync services,
 * and the architecture index support. Each collaborator is created lazily under
 * [LazyThreadSafetyMode.PUBLICATION] so concurrent first-touches from EDT and background
 * threads remain safe without paying the synchronized-lazy cost on hot paths.
 */
internal class InfrastructureComposition(
    private val project: Project,
    private val logger: Logger,
    private val testOverrides: LinkGraphProjectTestOverrides,
) {
    val runtimeSupport by lazy(LazyThreadSafetyMode.PUBLICATION) {
        LinkGraphProjectRuntimeSupport(
            project = project,
            logger = logger,
            openSettingsOverrideProvider = { testOverrides.openSettings },
            effectiveGenerationSettingsOverrideProvider = { testOverrides.effectiveGenerationSettings },
        )
    }

    val presentationProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        project.getService(GraphEditorPresentationProvider::class.java)
    }

    val editorSnapshotProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.editorSnapshotProvider()
    }

    val applicationSnapshotProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.applicationSnapshotProvider()
    }

    val toolGraphSnapshotProvider by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.toolGraphSnapshotProvider()
    }

    val workspaceGraphCommitter by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.workspaceGraphCommitter()
    }

    val eventSink by lazy(LazyThreadSafetyMode.PUBLICATION) {
        presentationProvider.eventSink()
    }

    val artifactStore by lazy(LazyThreadSafetyMode.PUBLICATION) {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    }

    val asyncRequestLifecycle by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AsyncRequestLifecycleSupport(
            project = project,
            timeoutOverrideProvider = { testOverrides.asyncRequestTimeoutMillis },
        )
    }

    val mermaidImporter by lazy(LazyThreadSafetyMode.PUBLICATION) { MermaidImporter() }
    val mermaidValidator by lazy(LazyThreadSafetyMode.PUBLICATION) { MermaidValidator() }
    val mermaidExporter by lazy(LazyThreadSafetyMode.PUBLICATION) { MermaidExporter() }

    val graphDiffer by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphDiffer() }
    val syncPreviewPlanner by lazy(LazyThreadSafetyMode.PUBLICATION) { SyncPreviewPlanner() }
    val graphPatchApplyService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphPatchApplyService() }
    val graphGenerationService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphGenerationService() }
    val graphQaPatchService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphQaPatchService() }
    val draftWorkbenchService by lazy(LazyThreadSafetyMode.PUBLICATION) { DraftWorkbenchService() }
    val riskResolutionService by lazy(LazyThreadSafetyMode.PUBLICATION) { RiskResolutionService() }
    val graphDiffPatchService by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphDiffPatchService() }
    val graphBeautificationService: GraphBeautificationService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DefaultGraphBeautificationService()
    }
    val codeGenerationService by lazy(LazyThreadSafetyMode.PUBLICATION) { CodeGenerationService() }
    val codeDraftWriterService by lazy(LazyThreadSafetyMode.PUBLICATION) { CodeDraftWriterService(project) }
    val graphDiagnosticsLogger by lazy(LazyThreadSafetyMode.PUBLICATION) { GraphDiagnosticsLogger(logger) }
    val debugGraphFactory by lazy(LazyThreadSafetyMode.PUBLICATION) { DebugGraphFactory() }

    val architectureIndexSupport by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ArchitectureIndexWorkflowSupport(project)
    }

    val planningContextFactory by lazy(LazyThreadSafetyMode.PUBLICATION) {
        PlanningContextFactory(
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            graphGenerationService = graphGenerationService,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            projectBasePathProvider = { project.basePath },
        )
    }
}
