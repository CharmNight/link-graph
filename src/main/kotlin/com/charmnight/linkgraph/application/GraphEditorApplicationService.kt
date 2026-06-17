package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.command.ApplicationCommandDispatcher
import com.charmnight.linkgraph.application.composition.ApplicationCommandComposition
import com.charmnight.linkgraph.application.composition.ApplicationWorkflowComposition
import com.charmnight.linkgraph.application.composition.ApplicationWorkflows
import com.charmnight.linkgraph.application.composition.InfrastructureComposition
import com.charmnight.linkgraph.application.composition.WorkflowComposition
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftApplyWorkflow
import com.charmnight.linkgraph.application.workflow.generation.CodeDraftGenerationWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanDiscussionWorkflow
import com.charmnight.linkgraph.application.workflow.generation.GenerationPlanWorkflow
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level composition root for Link Graph workflows and shared collaborators.
 *
 * This service owns object assembly only. Production entrypoints should depend on the focused command
 * services that wrap these workflows, not on a broad project-service facade. Per-workflow wiring lives
 * in [WorkflowComposition]; shared infrastructure collaborators live in [InfrastructureComposition].
 */
@Service(Service.Level.PROJECT)
internal class GraphEditorApplicationService(
    private val project: Project,
) : Disposable {
    private val testOverrides: LinkGraphProjectTestOverrides
        get() = project.getService(LinkGraphProjectTestOverrides::class.java)

    private val infrastructure: InfrastructureComposition by lazy(LazyThreadSafetyMode.PUBLICATION) {
        InfrastructureComposition(
            project = project,
            logger = logger,
            testOverrides = testOverrides,
        )
    }

    private val workflows: WorkflowComposition by lazy(LazyThreadSafetyMode.PUBLICATION) {
        WorkflowComposition(
            project = project,
            logger = logger,
            infrastructure = infrastructure,
            testOverrides = testOverrides,
        )
    }

    private val workflowComposition by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ApplicationWorkflowComposition(
            workflowsProvider = {
                ApplicationWorkflows(
                    subjectFlow = workflows.subjectFlow,
                    workspaceChangeCoordinator = workflows.workspaceChangeCoordinator,
                    workspaceFlow = workflows.workspaceFlow,
                    draftPatchFlow = workflows.draftPatchFlow,
                    generationPlanFlow = workflows.generationPlanFlow,
                    generationDiscussionFlow = workflows.generationDiscussionFlow,
                    codeDraftGenerationFlow = workflows.codeDraftGenerationFlow,
                    codeDraftApplyFlow = workflows.codeDraftApplyFlow,
                    reviewFlow = workflows.reviewFlow,
                    sourceNavigationFlow = workflows.sourceNavigationFlow,
                    invocationExpansionFlow = workflows.invocationExpansionFlow,
                    architectureGraphFlow = workflows.architectureGraphFlow,
                    classDiagramFlow = workflows.classDiagramFlow,
                    reviewGraphFlow = workflows.reviewGraphFlow,
                    confirmedDraftCoordinator = workflows.confirmedDraftCoordinator,
                    debugFlow = workflows.debugFlow,
                )
            },
        )
    }

    val commandDispatcher: ApplicationCommandDispatcher by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ApplicationCommandComposition(
            workflows = workflowComposition.workflows(),
            openCodeDraftNativeDiffOverrideProvider = { testOverrides.openCodeDraftNativeDiff },
        ).dispatcher()
    }

    override fun dispose() {
        workflows.subjectFlow.dispose()
    }

    companion object {
        const val DEBUG_ANALYSIS_DISPLAY_MODE_ENV: String = "LINKGRAPH_DEBUG_ANALYSIS_DISPLAY_MODE"
        private val logger = Logger.getInstance(GraphEditorApplicationService::class.java)
    }
}
