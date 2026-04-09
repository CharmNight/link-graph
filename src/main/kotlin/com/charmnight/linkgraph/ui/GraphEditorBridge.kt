package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.intellij.openapi.project.Project

/**
 * JCEF 前端与项目级服务之间的消息桥。
 * 前端只认识简单消息，真正的状态变更、文件导航和生成动作都从这里转给项目服务。
 */
class GraphEditorBridge(
    /** 当前桥接器所属项目。 */
    private val project: Project,
    /** 前端准备完成时触发的回调。 */
    private val onFrontendReady: (Long?) -> Unit = {},
    /** 前端确认收到快照后的回调。 */
    private val onSnapshotAck: (Long) -> Unit = {},
) {
    /** 编辑器状态服务。 */
    private val stateService: GraphEditorStateService = project.getService(GraphEditorStateService::class.java)
    /** 项目级业务服务。 */
    private val projectService: LinkGraphProjectService = project.getService(LinkGraphProjectService::class.java)

    /** 前端页面加载完成后登记入口地址。 */
    fun onFrontendLoaded(entryUrl: String) {
        stateService.markFrontendLoaded(entryUrl)
    }

    /**
     * 把前端消息分发到对应的项目服务或状态服务。
     * 这里不做复杂业务判断，只负责路由与最小上下文衔接。
     */
    fun dispatch(message: GraphEditorMessage) {
        when (message) {
            is GraphEditorMessage.LoadGraph -> projectService.loadGraph(message.graph, message.source)
            is GraphEditorMessage.ImportMermaid -> projectService.importMermaid(message.mermaid)
            is GraphEditorMessage.ExportMermaid -> projectService.exportMermaid()
            is GraphEditorMessage.ShowDiffMode -> projectService.showDiffMode()
            is GraphEditorMessage.NodeSelected -> stateService.selectNode(message.nodeId)
            is GraphEditorMessage.GraphChanged -> projectService.handleFrontendGraphChanged(message.graph)
            is GraphEditorMessage.LayoutChanged -> projectService.handleFrontendLayoutChanged(message.positions)
            is GraphEditorMessage.FrontendReady -> onFrontendReady(message.lastAppliedRevision)
            is GraphEditorMessage.SnapshotAck -> onSnapshotAck(message.revision)
            is GraphEditorMessage.RequestSourceNavigation -> projectService.requestSourceNavigation(message.nodeId)
            is GraphEditorMessage.RequestExpandOverflowNode -> projectService.requestExpandOverflowNode(message.nodeId)
            is GraphEditorMessage.RequestSyncPreview -> projectService.requestSyncPreview()
            is GraphEditorMessage.RequestAudit -> projectService.requestAuditAsync(message.question, message.selectedNodeIds)
            is GraphEditorMessage.RequestDiffReview -> projectService.requestDiffReviewAsync(
                message.question,
                message.selectedDiffItemIds,
            )
            is GraphEditorMessage.RequestGraphBeautification -> projectService.requestGraphBeautificationAsync(
                goal = message.goal,
                preferredStyle = message.preferredStyle,
                explanationFocus = message.explanationFocus,
            )
            is GraphEditorMessage.GraphBeautificationResult -> stateService.markGraphBeautificationResult(message.result)
            is GraphEditorMessage.ApplyDraftPatchPreview -> projectService.applyDraftPatchPreview(message.operationIds)
            is GraphEditorMessage.ClearDraftPatchPreview -> projectService.clearDraftPatchPreview()
            is GraphEditorMessage.RestoreDraftPatchPreview -> projectService.restoreDraftPatchPreview(
                LinkGraphProjectService.DraftPatchPreviewSource.valueOf(message.source.name),
            )
            is GraphEditorMessage.UndoLastDraftPatchApply -> projectService.undoLastDraftPatchApply()
            is GraphEditorMessage.RequestGenerationPlan -> projectService.requestGenerationPlanAsync()
            is GraphEditorMessage.RequestCodeDrafts -> projectService.requestCodeDraftsAsync()
            is GraphEditorMessage.RequestCurrentMethodGraph -> projectService.loadCurrentMethodGraphAsync()
            is GraphEditorMessage.RequestAnalysisDisplayMode -> projectService.requestAnalysisDisplayMode(message.displayMode)
            is GraphEditorMessage.OpenSettings -> projectService.openSettings()
            is GraphEditorMessage.ApplyCodeDrafts -> projectService.applyCodeDrafts()
            is GraphEditorMessage.ApplySingleCodeDraft -> projectService.applySingleCodeDraft(message.draftId)
            is GraphEditorMessage.RequestDraftNavigation -> projectService.requestDraftNavigation(message.targetPath)
        }
    }

    /** 兼容旧接口，直接触发加载图消息。 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        dispatch(GraphEditorMessage.LoadGraph(graph, source))
    }

    /** 返回当前桥接器观察到的最新状态快照。 */
    fun currentState(): GraphEditorStateService.Snapshot = stateService.snapshot()
}
