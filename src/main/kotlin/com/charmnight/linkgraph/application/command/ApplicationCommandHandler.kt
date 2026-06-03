package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
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
import com.charmnight.linkgraph.application.workflow.review.ReviewGraphWorkflow

internal interface ApplicationCommandHandler {
    fun canHandle(command: ApplicationCommand<*>): Boolean
    fun handle(command: ApplicationCommand<*>): Any?
}

internal class SubjectApplicationCommandHandler(
    private val subjectFlow: SubjectGraphWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.PreviewCurrentEditorSubjectKind ||
            command is ApplicationCommand.AddCurrentEditorContextNode ||
            command is ApplicationCommand.LoadCurrentEditorContextGraph ||
            command is ApplicationCommand.RequestExpandOverflowNode ||
            command is ApplicationCommand.RequestAnalysisDisplayMode

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.PreviewCurrentEditorSubjectKind ->
                subjectFlow.previewCurrentEditorSubjectKind(command.editor)
            ApplicationCommand.AddCurrentEditorContextNode ->
                subjectFlow.addCurrentEditorContextNode()
            is ApplicationCommand.LoadCurrentEditorContextGraph ->
                subjectFlow.loadCurrentEditorContextGraphAsync(command.editor)
            is ApplicationCommand.RequestExpandOverflowNode ->
                subjectFlow.requestExpandOverflowNode(command.nodeId)
            is ApplicationCommand.RequestAnalysisDisplayMode ->
                subjectFlow.requestAnalysisDisplayMode(command.displayMode)
            else -> unhandled(command)
        }
}

internal class IndexedGraphApplicationCommandHandler(
    private val architectureGraphFlow: ArchitectureGraphWorkflow,
    private val classDiagramFlow: ClassDiagramWorkflow,
    private val reviewGraphFlow: ReviewGraphWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestIndexedGraph

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.RequestIndexedGraph -> {
                when (command.request.view) {
                    IndexedGraphView.ARCHITECTURE -> architectureGraphFlow.requestIndexedGraph(command.request)
                    IndexedGraphView.CLASS_DIAGRAM -> classDiagramFlow.requestIndexedGraph(command.request)
                    IndexedGraphView.REVIEW -> reviewGraphFlow.requestIndexedGraph(command.request)
                }
            }
            else -> unhandled(command)
        }
}

internal class WorkspaceApplicationCommandHandler(
    private val workspaceFlow: GraphWorkspaceWorkflow,
    private val workspaceChangeCoordinator: WorkspaceChangeCoordinator,
    private val eventSink: GraphEditorApplicationEventSink,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.LoadGraph ||
            command is ApplicationCommand.ImportMermaid ||
            command is ApplicationCommand.ExportMermaid ||
            command is ApplicationCommand.ShowDiffMode ||
            command is ApplicationCommand.ApplyGraphEditScript ||
            command is ApplicationCommand.LayoutChanged ||
            command is ApplicationCommand.UpdateWorkbenchSectionPreference ||
            command is ApplicationCommand.RequestSyncPreview

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.LoadGraph -> {
                workspaceChangeCoordinator.resetWorkspaceGraphContext()
                workspaceFlow.loadGraph(command.graph, command.source)
            }
            is ApplicationCommand.ImportMermaid -> {
                workspaceChangeCoordinator.resetWorkspaceGraphContext()
                workspaceFlow.importMermaid(command.mermaid)
            }
            ApplicationCommand.ExportMermaid -> workspaceFlow.exportMermaid()
            ApplicationCommand.ShowDiffMode ->
                workspaceChangeCoordinator.invalidateRequests().let { workspaceFlow.showDiffMode() }
            is ApplicationCommand.ApplyGraphEditScript -> {
                workspaceChangeCoordinator.resetWorkspaceGraphContext()
                workspaceFlow.handleFrontendEditScript(command.script)
            }
            is ApplicationCommand.LayoutChanged ->
                workspaceFlow.handleFrontendLayoutChanged(command.positions)
            is ApplicationCommand.UpdateWorkbenchSectionPreference ->
                eventSink.emit(GraphEditorApplicationEvent.WorkbenchSectionPreferencesChanged(command.preferences))
            ApplicationCommand.RequestSyncPreview -> workspaceFlow.requestSyncPreview()
            else -> unhandled(command)
        }
}

internal class SourceNavigationApplicationCommandHandler(
    private val sourceNavigationFlow: SourceNavigationWorkflow,
    private val invocationExpansionFlow: InvocationExpansionWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestSourceNavigation ||
            command is ApplicationCommand.RequestExpandInvocation ||
            command is ApplicationCommand.RequestRemoveInvocationExpansion ||
            command is ApplicationCommand.OpenSettings

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.RequestSourceNavigation ->
                sourceNavigationFlow.requestSourceNavigation(command.nodeId)
            is ApplicationCommand.RequestExpandInvocation ->
                invocationExpansionFlow.requestExpandInvocation(command.nodeId)
            is ApplicationCommand.RequestRemoveInvocationExpansion ->
                invocationExpansionFlow.requestRemoveInvocationExpansion(command.expansionId)
            ApplicationCommand.OpenSettings -> sourceNavigationFlow.openSettings()
            else -> unhandled(command)
        }
}

internal class ReviewApplicationCommandHandler(
    private val reviewFlow: ReviewWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestQa ||
            command is ApplicationCommand.RetryLastQaRequest ||
            command is ApplicationCommand.ResolveInvestigationThread ||
            command is ApplicationCommand.RequestDiffReview ||
            command is ApplicationCommand.RequestGraphBeautification

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.RequestQa -> reviewFlow.requestQaAsync(
                question = command.question,
                selectedNodeIds = command.selectedNodeIds,
                sourceThreadId = command.sourceThreadId,
                mode = command.mode,
            )
            ApplicationCommand.RetryLastQaRequest -> reviewFlow.retryLastQaRequestAsync()
            is ApplicationCommand.ResolveInvestigationThread -> reviewFlow.resolveInvestigationThread(
                threadId = command.threadId,
                status = command.status,
                note = command.note,
            )
            is ApplicationCommand.RequestDiffReview -> reviewFlow.requestDiffReviewAsync(
                question = command.question,
                selectedDiffItemIds = command.selectedDiffItemIds,
            )
            is ApplicationCommand.RequestGraphBeautification -> reviewFlow.requestGraphBeautificationAsync(
                goal = command.goal,
                preferredStyle = command.preferredStyle,
                explanationFocus = command.explanationFocus,
                focusNodeId = command.focusNodeId,
                followUp = command.followUp,
                granularity = command.granularity,
            )
            else -> unhandled(command)
        }
}

internal class DraftApplicationCommandHandler(
    private val confirmedDraftCoordinator: ConfirmedDraftChangeCoordinator,
    private val draftPatchFlow: DraftPatchWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.ConfirmQaCandidateChange ||
            command is ApplicationCommand.UnconfirmQaCandidateChange ||
            command is ApplicationCommand.ApplyDraftPatchPreview ||
            command is ApplicationCommand.ClearDraftPatchPreview ||
            command is ApplicationCommand.RestoreDraftPatchPreview ||
            command is ApplicationCommand.UndoLastDraftPatchApply

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.ConfirmQaCandidateChange ->
                confirmedDraftCoordinator.confirm(command.changeId)
            is ApplicationCommand.UnconfirmQaCandidateChange ->
                confirmedDraftCoordinator.unconfirm(command.changeId)
            is ApplicationCommand.ApplyDraftPatchPreview ->
                draftPatchFlow.applyDraftPatchPreview(command.operationIds)
            ApplicationCommand.ClearDraftPatchPreview ->
                draftPatchFlow.clearDraftPatchPreview()
            is ApplicationCommand.RestoreDraftPatchPreview ->
                draftPatchFlow.restoreDraftPatchPreview(command.source)
            ApplicationCommand.UndoLastDraftPatchApply ->
                draftPatchFlow.undoLastDraftPatchApply()
            else -> unhandled(command)
        }
}

internal class GenerationApplicationCommandHandler(
    private val generationPlanFlow: GenerationPlanWorkflow,
    private val generationDiscussionFlow: GenerationPlanDiscussionWorkflow,
    private val codeDraftGenerationFlow: CodeDraftGenerationWorkflow,
    private val codeDraftApplyFlow: CodeDraftApplyWorkflow,
    private val openCodeDraftNativeDiffOverrideProvider: () -> ((String) -> Unit)? = { null },
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.RequestGenerationPlan ||
            command is ApplicationCommand.RequestGenerationPlanDiscussion ||
            command is ApplicationCommand.RequestCodeDrafts ||
            command is ApplicationCommand.ApplyCodeDrafts ||
            command is ApplicationCommand.ApplySingleCodeDraft ||
            command is ApplicationCommand.OpenCodeDraftNativeDiff ||
            command is ApplicationCommand.RequestDraftNavigation

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            ApplicationCommand.RequestGenerationPlan ->
                generationPlanFlow.requestGenerationPlanAsync()
            is ApplicationCommand.RequestGenerationPlanDiscussion ->
                generationDiscussionFlow.requestGenerationPlanDiscussionAsync(command.question, command.focusItemId)
            ApplicationCommand.RequestCodeDrafts ->
                codeDraftGenerationFlow.requestCodeDraftsAsync()
            ApplicationCommand.ApplyCodeDrafts ->
                codeDraftApplyFlow.applyCodeDrafts()
            is ApplicationCommand.ApplySingleCodeDraft ->
                codeDraftApplyFlow.applySingleCodeDraft(command.draftId)
            is ApplicationCommand.OpenCodeDraftNativeDiff ->
                openCodeDraftNativeDiffOverrideProvider()?.invoke(command.draftId)
                    ?: codeDraftApplyFlow.openCodeDraftNativeDiff(command.draftId)
            is ApplicationCommand.RequestDraftNavigation ->
                codeDraftApplyFlow.requestDraftNavigation(command.targetPath)
            else -> unhandled(command)
        }
}

internal class DebugApplicationCommandHandler(
    private val debugFlow: ProjectDebugWorkflow,
) : ApplicationCommandHandler {
    override fun canHandle(command: ApplicationCommand<*>): Boolean =
        command is ApplicationCommand.PrepareDebugRequestedAnalysisDisplayMode ||
            command is ApplicationCommand.LoadDebugMethodGraphBySignature ||
            command is ApplicationCommand.LoadDebugGraph

    override fun handle(command: ApplicationCommand<*>): Any? =
        when (command) {
            is ApplicationCommand.PrepareDebugRequestedAnalysisDisplayMode ->
                debugFlow.prepareDebugRequestedAnalysisDisplayModeIfPresent(command.envName)
            is ApplicationCommand.LoadDebugMethodGraphBySignature ->
                debugFlow.loadDebugMethodGraphBySignatureAsync(command.signature)
            is ApplicationCommand.LoadDebugGraph ->
                debugFlow.loadDebugGraph(command.mode)
            else -> unhandled(command)
        }
}

private fun unhandled(command: ApplicationCommand<*>): Nothing =
    error("Command ${command::class.qualifiedName} was routed to the wrong handler")
