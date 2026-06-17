package com.charmnight.linkgraph.application.composition

import com.charmnight.linkgraph.application.command.ApplicationCommandDispatcher
import com.charmnight.linkgraph.application.command.AssistantApplicationCommandHandler
import com.charmnight.linkgraph.application.command.DebugApplicationCommandHandler
import com.charmnight.linkgraph.application.command.DraftApplicationCommandHandler
import com.charmnight.linkgraph.application.command.GenerationApplicationCommandHandler
import com.charmnight.linkgraph.application.command.IndexedGraphApplicationCommandHandler
import com.charmnight.linkgraph.application.command.ReviewApplicationCommandHandler
import com.charmnight.linkgraph.application.command.SourceNavigationApplicationCommandHandler
import com.charmnight.linkgraph.application.command.SubjectApplicationCommandHandler
import com.charmnight.linkgraph.application.command.WorkflowAssistantTaskExecutor
import com.charmnight.linkgraph.application.command.WorkspaceApplicationCommandHandler
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
import com.charmnight.linkgraph.application.workflow.ReviewGraphWorkflow

internal class ApplicationCommandComposition(
    private val workflows: ApplicationWorkflows,
    private val openCodeDraftNativeDiffOverrideProvider: () -> ((String) -> Unit)? = { null },
) {
    fun dispatcher(): ApplicationCommandDispatcher =
        ApplicationCommandDispatcher(
            listOf(
                SubjectApplicationCommandHandler(workflows.subjectFlow),
                IndexedGraphApplicationCommandHandler(
                    architectureGraphFlow = workflows.architectureGraphFlow,
                    classDiagramFlow = workflows.classDiagramFlow,
                    reviewGraphFlow = workflows.reviewGraphFlow,
                ),
                WorkspaceApplicationCommandHandler(
                    workspaceFlow = workflows.workspaceFlow,
                    workspaceChangeCoordinator = workflows.workspaceChangeCoordinator,
                ),
                SourceNavigationApplicationCommandHandler(
                    sourceNavigationFlow = workflows.sourceNavigationFlow,
                    invocationExpansionFlow = workflows.invocationExpansionFlow,
                ),
                AssistantApplicationCommandHandler(
                    WorkflowAssistantTaskExecutor(
                        reviewFlow = workflows.reviewFlow,
                        reviewGraphFlow = workflows.reviewGraphFlow,
                        generationPlanFlow = workflows.generationPlanFlow,
                        generationDiscussionFlow = workflows.generationDiscussionFlow,
                    ),
                ),
                ReviewApplicationCommandHandler(workflows.reviewFlow),
                DraftApplicationCommandHandler(
                    confirmedDraftCoordinator = workflows.confirmedDraftCoordinator,
                    draftPatchFlow = workflows.draftPatchFlow,
                ),
                GenerationApplicationCommandHandler(
                    generationPlanFlow = workflows.generationPlanFlow,
                    generationDiscussionFlow = workflows.generationDiscussionFlow,
                    codeDraftGenerationFlow = workflows.codeDraftGenerationFlow,
                    codeDraftApplyFlow = workflows.codeDraftApplyFlow,
                    openCodeDraftNativeDiffOverrideProvider = openCodeDraftNativeDiffOverrideProvider,
                ),
                DebugApplicationCommandHandler(workflows.debugFlow),
            ),
        )
}

internal data class ApplicationWorkflows(
    val subjectFlow: SubjectGraphWorkflow,
    val workspaceChangeCoordinator: WorkspaceChangeCoordinator,
    val workspaceFlow: GraphWorkspaceWorkflow,
    val draftPatchFlow: DraftPatchWorkflow,
    val generationPlanFlow: GenerationPlanWorkflow,
    val generationDiscussionFlow: GenerationPlanDiscussionWorkflow,
    val codeDraftGenerationFlow: CodeDraftGenerationWorkflow,
    val codeDraftApplyFlow: CodeDraftApplyWorkflow,
    val reviewFlow: ReviewWorkflow,
    val sourceNavigationFlow: SourceNavigationWorkflow,
    val invocationExpansionFlow: InvocationExpansionWorkflow,
    val architectureGraphFlow: ArchitectureGraphWorkflow,
    val classDiagramFlow: ClassDiagramWorkflow,
    val reviewGraphFlow: ReviewGraphWorkflow,
    val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator,
    val debugFlow: ProjectDebugWorkflow,
)
