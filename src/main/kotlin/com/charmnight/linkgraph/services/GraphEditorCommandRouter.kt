package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.ui.GraphEditorMessage
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphLayoutPosition
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.StepGranularity
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
    private val projectService: LinkGraphProjectService by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(LinkGraphProjectService::class.java)
    }

    private val testOverrides: LinkGraphProjectTestOverrides by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(LinkGraphProjectTestOverrides::class.java)
    }

    fun dispatch(message: GraphEditorMessage) {
        when (message) {
            is GraphEditorMessage.LoadGraph -> loadGraph(message.graph, message.source)
            is GraphEditorMessage.ImportMermaid -> projectService.importMermaid(message.mermaid)
            GraphEditorMessage.ExportMermaid -> projectService.exportMermaid()
            GraphEditorMessage.ShowDiffMode -> projectService.showDiffMode()
            is GraphEditorMessage.ApplyGraphEditScript -> handleFrontendGraphEditScript(message.script)
            is GraphEditorMessage.LayoutChanged -> handleFrontendLayoutChanged(message.positions)
            is GraphEditorMessage.RequestSourceNavigation -> requestSourceNavigation(message.nodeId)
            is GraphEditorMessage.RequestExpandOverflowNode -> projectService.requestExpandOverflowNode(message.nodeId)
            GraphEditorMessage.RequestSyncPreview -> requestSyncPreview()
            is GraphEditorMessage.RequestAudit -> requestAuditAsync(
                message.question,
                message.selectedNodeIds,
                message.sourceThreadId,
                message.mode,
            )
            GraphEditorMessage.RetryLastAuditRequest -> projectService.retryLastAuditRequestAsync()
            is GraphEditorMessage.ConfirmAuditCandidateChange -> projectService.confirmAuditCandidateChange(message.changeId)
            is GraphEditorMessage.UnconfirmAuditCandidateChange -> projectService.unconfirmAuditCandidateChange(message.changeId)
            is GraphEditorMessage.ResolveInvestigationThread -> projectService.resolveInvestigationThread(
                threadId = message.threadId,
                status = message.resolutionStatus,
                note = message.note,
            )
            is GraphEditorMessage.RequestDiffReview -> requestDiffReviewAsync(message.question, message.selectedDiffItemIds)
            is GraphEditorMessage.RequestGraphBeautification -> requestGraphBeautificationAsync(
                goal = message.goal,
                preferredStyle = message.preferredStyle,
                explanationFocus = message.explanationFocus,
                followUp = message.followUp,
                granularity = message.granularity,
            )
            is GraphEditorMessage.ApplyDraftPatchPreview -> projectService.applyDraftPatchPreview(message.operationIds)
            GraphEditorMessage.ClearDraftPatchPreview -> clearDraftPatchPreview()
            is GraphEditorMessage.RestoreDraftPatchPreview -> projectService.restoreDraftPatchPreview(
                LinkGraphProjectService.DraftPatchPreviewSource.valueOf(message.source.name),
            )
            GraphEditorMessage.UndoLastDraftPatchApply -> projectService.undoLastDraftPatchApply()
            GraphEditorMessage.RequestGenerationPlan -> requestGenerationPlanAsync()
            is GraphEditorMessage.RequestGenerationPlanDiscussion -> requestGenerationPlanDiscussionAsync(
                message.question,
                message.focusItemId,
            )
            GraphEditorMessage.RequestCodeDrafts -> requestCodeDraftsAsync()
            GraphEditorMessage.RequestCurrentEditorContextGraph -> projectService.loadCurrentEditorContextGraphAsync()
            is GraphEditorMessage.RequestAnalysisDisplayMode -> projectService.requestAnalysisDisplayMode(message.displayMode)
            is GraphEditorMessage.UpdateWorkbenchSectionPreference -> updateWorkbenchSectionPreference(
                message.sectionId,
                message.expanded,
            )
            GraphEditorMessage.OpenSettings -> projectService.openSettings()
            GraphEditorMessage.ApplyCodeDrafts -> applyCodeDrafts()
            is GraphEditorMessage.ApplySingleCodeDraft -> applySingleCodeDraft(message.draftId)
            is GraphEditorMessage.OpenCodeDraftNativeDiff -> openCodeDraftNativeDiff(message.draftId)
            is GraphEditorMessage.RequestDraftNavigation -> requestDraftNavigation(message.targetPath)
            is GraphEditorMessage.FrontendReady,
            is GraphEditorMessage.SnapshotAck,
            is GraphEditorMessage.NodeSelected,
            is GraphEditorMessage.GraphBeautificationResult,
            -> error("GraphEditorCommandRouter 不处理前端生命周期或纯状态回写消息: ${message::class.simpleName}")
        }
    }

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        projectService.resetWorkspaceGraphContext()
        projectService.graphWorkspaceWorkflow.loadGraph(graph, source)
    }

    fun handleFrontendGraphEditScript(script: com.charmnight.linkgraph.ui.GraphEditScript) {
        projectService.resetWorkspaceGraphContext()
        projectService.graphWorkspaceWorkflow.handleFrontendEditScript(script)
    }

    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        projectService.graphWorkspaceWorkflow.handleFrontendLayoutChanged(positions)
    }

    fun requestSourceNavigation(nodeId: String) =
        projectService.sourceNavigationWorkflow.requestSourceNavigation(nodeId)

    fun requestSyncPreview(): List<SyncPreviewItem> = projectService.graphWorkspaceWorkflow.requestSyncPreview()

    fun requestAuditAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
    ) {
        projectService.reviewWorkflow.requestAuditAsync(question, selectedNodeIds, sourceThreadId, mode)
    }

    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        projectService.reviewWorkflow.requestDiffReviewAsync(question, selectedDiffItemIds)
    }

    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ) {
        projectService.reviewWorkflow.requestGraphBeautificationAsync(
            goal = goal,
            preferredStyle = preferredStyle,
            explanationFocus = explanationFocus,
            followUp = followUp,
            granularity = granularity,
        )
    }

    fun requestGenerationPlanAsync() {
        projectService.generationWorkflow.requestGenerationPlanAsync()
    }

    fun requestGenerationPlanDiscussionAsync(
        question: String,
        focusItemId: String? = null,
    ) {
        projectService.generationWorkflow.requestGenerationPlanDiscussionAsync(question, focusItemId)
    }

    fun requestCodeDraftsAsync() {
        projectService.generationWorkflow.requestCodeDraftsAsync()
    }

    fun applyCodeDrafts() {
        projectService.generationWorkflow.applyCodeDrafts()
    }

    fun applySingleCodeDraft(draftId: String) {
        projectService.generationWorkflow.applySingleCodeDraft(draftId)
    }

    fun openCodeDraftNativeDiff(draftId: String) {
        testOverrides.openCodeDraftNativeDiff?.invoke(draftId)
            ?: projectService.generationWorkflow.openCodeDraftNativeDiff(draftId)
    }

    fun requestDraftNavigation(targetPath: String) {
        projectService.generationWorkflow.requestDraftNavigation(targetPath)
    }

    fun updateWorkbenchSectionPreference(
        sectionId: String,
        expanded: Boolean,
    ) {
        val nextPreferences = project.getService(WorkbenchLayoutPreferencesService::class.java).update(sectionId, expanded)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "更新工作台分区偏好: sectionId=$sectionId, expanded=$expanded, nextPreferences=$nextPreferences"
        }
        project.getService(GraphEditorStateService::class.java).workbench.markWorkbenchSectionPreferences(nextPreferences)
        project.getService(GraphEditorSyncNotifier::class.java).requestSync()
    }

    fun clearDraftPatchPreview() {
        projectService.draftPatchWorkflow.clearDraftPatchPreview()
    }

    private companion object {
        private val logger = Logger.getInstance(GraphEditorCommandRouter::class.java)
    }
}
