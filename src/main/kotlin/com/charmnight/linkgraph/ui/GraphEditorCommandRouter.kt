package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.workbench.WorkbenchLayoutPreferencesService
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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

    fun dispatch(message: GraphEditorMessage) {
        when (message) {
            is GraphEditorMessage.LoadGraph -> applicationService.loadGraph(message.graph, message.source)
            is GraphEditorMessage.ImportMermaid -> applicationService.importMermaid(message.mermaid)
            GraphEditorMessage.ExportMermaid -> applicationService.exportMermaid()
            GraphEditorMessage.ShowDiffMode -> applicationService.showDiffMode()
            is GraphEditorMessage.ApplyGraphEditScript -> applicationService.handleFrontendEditScript(message.script)
            is GraphEditorMessage.LayoutChanged -> applicationService.handleFrontendLayoutChanged(message.positions)
            is GraphEditorMessage.RequestSourceNavigation -> applicationService.requestSourceNavigation(message.nodeId)
            is GraphEditorMessage.RequestExpandOverflowNode -> applicationService.requestExpandOverflowNode(message.nodeId)
            is GraphEditorMessage.RequestExpandInvocation -> applicationService.requestExpandInvocation(message.nodeId)
            is GraphEditorMessage.RequestRemoveInvocationExpansion ->
                applicationService.requestRemoveInvocationExpansion(message.expansionId)
            GraphEditorMessage.RequestSyncPreview -> applicationService.requestSyncPreview()
            is GraphEditorMessage.RequestQa -> applicationService.requestQaAsync(
                message.question,
                message.selectedNodeIds,
                message.sourceThreadId,
                message.mode,
            )
            GraphEditorMessage.RetryLastQaRequest -> applicationService.retryLastQaRequestAsync()
            is GraphEditorMessage.ConfirmQaCandidateChange -> applicationService.confirmQaCandidateChange(message.changeId)
            is GraphEditorMessage.UnconfirmQaCandidateChange -> applicationService.unconfirmQaCandidateChange(message.changeId)
            is GraphEditorMessage.ResolveInvestigationThread -> applicationService.resolveInvestigationThread(
                threadId = message.threadId,
                status = message.resolutionStatus,
                note = message.note,
            )
            is GraphEditorMessage.RequestDiffReview -> applicationService.requestDiffReviewAsync(message.question, message.selectedDiffItemIds)
            is GraphEditorMessage.RequestGraphBeautification -> applicationService.requestGraphBeautificationAsync(
                goal = message.goal,
                preferredStyle = message.preferredStyle,
                explanationFocus = message.explanationFocus,
                focusNodeId = message.focusNodeId,
                followUp = message.followUp,
                granularity = message.granularity,
            )
            is GraphEditorMessage.ApplyDraftPatchPreview -> applicationService.applyDraftPatchPreview(message.operationIds)
            GraphEditorMessage.ClearDraftPatchPreview -> applicationService.clearDraftPatchPreview()
            is GraphEditorMessage.RestoreDraftPatchPreview -> applicationService.restoreDraftPatchPreview(
                DraftPatchPreviewSource.valueOf(message.source.name),
            )
            GraphEditorMessage.UndoLastDraftPatchApply -> applicationService.undoLastDraftPatchApply()
            GraphEditorMessage.RequestGenerationPlan -> applicationService.requestGenerationPlanAsync()
            is GraphEditorMessage.RequestGenerationPlanDiscussion -> applicationService.requestGenerationPlanDiscussionAsync(
                message.question,
                message.focusItemId,
            )
            GraphEditorMessage.RequestCodeDrafts -> applicationService.requestCodeDraftsAsync()
            GraphEditorMessage.RequestCurrentEditorContextGraph -> applicationService.loadCurrentEditorContextGraphAsync()
            is GraphEditorMessage.RequestAnalysisDisplayMode -> applicationService.requestAnalysisDisplayMode(message.displayMode)
            is GraphEditorMessage.UpdateWorkbenchSectionPreference -> updateWorkbenchSectionPreference(
                message.sectionId,
                message.expanded,
            )
            GraphEditorMessage.OpenSettings -> applicationService.openSettings()
            GraphEditorMessage.ApplyCodeDrafts -> applicationService.applyCodeDrafts()
            is GraphEditorMessage.ApplySingleCodeDraft -> applicationService.applySingleCodeDraft(message.draftId)
            is GraphEditorMessage.OpenCodeDraftNativeDiff -> applicationService.openCodeDraftNativeDiff(message.draftId)
            is GraphEditorMessage.RequestDraftNavigation -> applicationService.requestDraftNavigation(message.targetPath)
            is GraphEditorMessage.FrontendReady,
            is GraphEditorMessage.SnapshotAck,
            is GraphEditorMessage.NodeSelected,
            is GraphEditorMessage.GraphBeautificationResult,
            -> error("GraphEditorCommandRouter 不处理前端生命周期或纯状态回写消息: ${message::class.simpleName}")
        }
    }

    fun updateWorkbenchSectionPreference(
        sectionId: String,
        expanded: Boolean,
    ) {
        val nextPreferences = project.getService(WorkbenchLayoutPreferencesService::class.java).update(sectionId, expanded)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "更新工作台分区偏好: sectionId=$sectionId, expanded=$expanded, nextPreferences=$nextPreferences"
        }
        applicationService.updateWorkbenchSectionPreference(nextPreferences)
    }

    private companion object {
        private val logger = Logger.getInstance(GraphEditorCommandRouter::class.java)
    }
}
