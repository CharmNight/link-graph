package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 图编辑器前后端桥接命令路由。
 * 只负责 bridge 命令分发，不承载项目级总装逻辑。
 */
@Service(Service.Level.PROJECT)
class GraphEditorCommandRouter(
    private val project: Project,
) {
    private val applicationService by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(GraphEditorApplicationService::class.java)
    }

    private val commandDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        applicationService.commandDispatcher
    }

    fun dispatch(message: GraphEditorMessage) {
        commandDispatcher.dispatch(message.toApplicationCommand())
    }

    private fun GraphEditorMessage.toApplicationCommand(): ApplicationCommand<*> =
        when (val current = this) {
            is GraphEditorMessage.LoadGraph -> ApplicationCommand.LoadGraph(current.graph, current.source)
            is GraphEditorMessage.ImportMermaid -> ApplicationCommand.ImportMermaid(current.mermaid)
            GraphEditorMessage.ExportMermaid -> ApplicationCommand.ExportMermaid
            GraphEditorMessage.ShowDiffMode -> ApplicationCommand.ShowDiffMode
            is GraphEditorMessage.ApplyGraphEditRequest -> ApplicationCommand.ApplyGraphEditRequest(current.request)
            is GraphEditorMessage.LayoutChanged -> ApplicationCommand.LayoutChanged(current.positions)
            is GraphEditorMessage.RequestSourceNavigation -> ApplicationCommand.RequestSourceNavigation(current.nodeId)
            is GraphEditorMessage.RequestExpandOverflowNode -> ApplicationCommand.RequestExpandOverflowNode(current.nodeId)
            is GraphEditorMessage.RequestExpandInvocation -> ApplicationCommand.RequestExpandInvocation(current.nodeId)
            is GraphEditorMessage.RequestRemoveInvocationExpansion ->
                ApplicationCommand.RequestRemoveInvocationExpansion(current.expansionId)
            GraphEditorMessage.RequestSyncPreview -> ApplicationCommand.RequestSyncPreview
            is GraphEditorMessage.RequestAssistantTask -> ApplicationCommand.RequestAssistantTask(
                actionId = current.actionId,
                intent = current.intent,
                sceneId = current.sceneId,
                prompt = current.prompt,
                selectedNodeIds = current.selectedNodeIds,
                selectedDiffItemIds = current.selectedDiffItemIds,
                target = current.target,
                explanationGranularity = current.explanationGranularity,
            )
            GraphEditorMessage.RetryLastQaRequest -> ApplicationCommand.RetryLastQaRequest
            is GraphEditorMessage.ConfirmQaCandidateChange -> ApplicationCommand.ConfirmQaCandidateChange(current.changeId)
            is GraphEditorMessage.UnconfirmQaCandidateChange -> ApplicationCommand.UnconfirmQaCandidateChange(current.changeId)
            is GraphEditorMessage.ResolveInvestigationThread -> ApplicationCommand.ResolveInvestigationThread(
                threadId = current.threadId,
                status = current.resolutionStatus,
                note = current.note,
            )
            is GraphEditorMessage.ApplyDraftPatchPreview -> ApplicationCommand.ApplyDraftPatchPreview(current.operationIds)
            GraphEditorMessage.ClearDraftPatchPreview -> ApplicationCommand.ClearDraftPatchPreview
            is GraphEditorMessage.RestoreDraftPatchPreview -> ApplicationCommand.RestoreDraftPatchPreview(
                DraftPatchPreviewSource.valueOf(current.source.name),
            )
            GraphEditorMessage.UndoLastDraftPatchApply -> ApplicationCommand.UndoLastDraftPatchApply
            GraphEditorMessage.RequestCodeDrafts -> ApplicationCommand.RequestCodeDrafts
            GraphEditorMessage.RequestCurrentEditorContextGraph -> ApplicationCommand.LoadCurrentEditorContextGraph()
            is GraphEditorMessage.RequestAnalysisDisplayMode -> ApplicationCommand.RequestAnalysisDisplayMode(current.displayMode)
            is GraphEditorMessage.RequestIndexedGraph -> ApplicationCommand.RequestIndexedGraph(current.request)
            GraphEditorMessage.OpenSettings -> ApplicationCommand.OpenSettings
            GraphEditorMessage.ApplyCodeDrafts -> ApplicationCommand.ApplyCodeDrafts
            is GraphEditorMessage.ApplySingleCodeDraft -> ApplicationCommand.ApplySingleCodeDraft(current.draftId)
            is GraphEditorMessage.OpenCodeDraftNativeDiff -> ApplicationCommand.OpenCodeDraftNativeDiff(current.draftId)
            is GraphEditorMessage.RequestDraftNavigation -> ApplicationCommand.RequestDraftNavigation(current.targetPath)
            is GraphEditorMessage.FrontendReady,
            is GraphEditorMessage.SnapshotAck,
            is GraphEditorMessage.NodeSelected,
            is GraphEditorMessage.GraphBeautificationResult,
            -> error("GraphEditorCommandRouter 不处理前端生命周期或纯状态回写消息: ${current::class.simpleName}")
        }
}
