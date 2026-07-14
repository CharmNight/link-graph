package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.workbench.RiskResolutionService

/**
 * 图谱编辑器应用事件投影器。
 *
 * 接收来自应用层的事件流，并根据事件类别路由到不同的工作台展示器，
 * 把抽象的应用事件转换为 UI 状态变更并触发浏览器同步刷新。
 */
internal class GraphEditorApplicationEventProjector(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit,
    private val riskResolutionService: RiskResolutionService = RiskResolutionService(),
) {
    /** 构造一个事件出口，把外部派发的事件转交给本投影器内部统一处理。 */
    fun eventSink(): GraphEditorApplicationEventSink =
        GraphEditorApplicationEventSink { event -> present(event) }

    /** 工作台状态展示器：负责工作区图谱加载/变更、布局、Mermaid 导入导出等展示。 */
    private fun workspacePresenter(): WorkspaceStatePresenter =
        WorkspaceStatePresenter(stateService, requestBrowserSync)

    /** 主体视图展示器：负责反馈消息、当前选中方法、调试图谱等展示。 */
    private fun subjectPresenter(): SubjectGraphStatePresenter =
        SubjectGraphStatePresenter(stateService, requestBrowserSync)

    /** 评审流程展示器：负责 QA、Diff 评审、代码美化等结果的展示。 */
    private fun reviewPresenter(): ReviewStatePresenter =
        ReviewStatePresenter(stateService, requestBrowserSync)

    /** 代码生成流程展示器：负责生成计划、代码草稿、流式预览等展示。 */
    private fun generationPresenter(): GenerationStatePresenter =
        GenerationStatePresenter(stateService, requestBrowserSync)

    /** 源码导航展示器：负责跳转请求、设置项打开等展示。 */
    private fun sourceNavigationPresenter(): SourceNavigationStatePresenter =
        SourceNavigationStatePresenter(stateService, requestBrowserSync)

    /** 架构视图展示器：负责索引图、类图、评审图等架构级视图的展示。 */
    private fun architecturePresenter(): ArchitectureGraphStatePresenter =
        ArchitectureGraphStatePresenter(stateService, requestBrowserSync)

    /** 草稿补丁展示器：负责草稿预览、应用、清除、撤销等结果的展示。 */
    private fun draftPatchPresenter(): DraftPatchStatePresenter =
        DraftPatchStatePresenter(stateService, requestBrowserSync)

    /** 已确认草稿展示器：结合风险解析服务，把确认/取消确认结果反映到 UI。 */
    private fun confirmedDraftPresenter(): ConfirmedDraftStatePresenter =
        ConfirmedDraftStatePresenter(
            stateService = stateService,
            draftValidationEvaluator = riskResolutionService::evaluateDraftValidation,
            codeEligibilityEvaluator = riskResolutionService::evaluateCodeEligibility,
            requestBrowserSync = requestBrowserSync,
        )

    /**
     * 事件分发入口：根据事件具体类型选择合适的展示器进行呈现。
     * 一个事件对应一个 when 分支，确保所有应用事件都能被路由到对应的 UI 更新逻辑。
     */
    private fun present(event: GraphEditorApplicationEvent) {
        when (event) {
            is GraphEditorApplicationEvent.WorkspaceGraphLoaded ->
                workspacePresenter().presentGraphLoaded(event.graph, event.source)
            is GraphEditorApplicationEvent.WorkspaceGraphChanged ->
                workspacePresenter().presentGraphChanged(
                    graph = event.graph,
                    selectedMethodSignature = event.selectedMethodSignature,
                    preserveDraftPatchUndo = event.preserveDraftPatchUndo,
                    workingGraphDirty = event.workingGraphDirty,
                    graphEditTransaction = event.graphEditTransaction,
                )
            is GraphEditorApplicationEvent.GraphEditRejected ->
                stateService.mutate { currentState -> currentState.withGraphEditRejected(event.rejection) }
            is GraphEditorApplicationEvent.WorkspaceLayoutChanged ->
                workspacePresenter().presentLayoutChanged(event.positions)
            is GraphEditorApplicationEvent.MermaidImported ->
                workspacePresenter().presentMermaidImported(event.mermaid, event.graph, event.issues)
            is GraphEditorApplicationEvent.MermaidExported ->
                workspacePresenter().presentMermaidExported(event.exported, event.copiedToClipboard)
            is GraphEditorApplicationEvent.DiffModeShown ->
                workspacePresenter().presentDiffMode(event.graph, event.diff)
            is GraphEditorApplicationEvent.SyncPreviewReady ->
                workspacePresenter().presentSyncPreview(event.items)
            is GraphEditorApplicationEvent.AsyncRequestsInvalidated -> {
                val changed = stateService.asyncRequests.invalidateRunningRequests(
                    qaRequestId = event.qaRequestId,
                    diffReviewRequestId = event.diffReviewRequestId,
                    beautificationRequestId = event.beautificationRequestId,
                    generationPlanRequestId = event.generationPlanRequestId,
                    generationPlanDiscussionRequestId = event.generationPlanDiscussionRequestId,
                    codeDraftRequestId = event.codeDraftRequestId,
                )
                if (changed) {
                    requestBrowserSync()
                }
            }
            is GraphEditorApplicationEvent.Feedback ->
                subjectPresenter().presentFeedback(event.level, event.message, event.preservePreviousStatusKind)
            is GraphEditorApplicationEvent.AnalysisDisplayModeChanged ->
                subjectPresenter().presentAnalysisDisplayMode(event.displayMode)
            is GraphEditorApplicationEvent.InvocationExpansionOpened -> {
                stateService.openInvocationExpansion(event.expansionId)
                requestBrowserSync()
            }
            is GraphEditorApplicationEvent.SelectedMethodChanged ->
                subjectPresenter().presentSelectedMethod(event.signature)
            is GraphEditorApplicationEvent.AnalysisOutcomeLoaded ->
                subjectPresenter().presentAnalysisOutcome(event.outcome, event.source)
            is GraphEditorApplicationEvent.IndexedGraphRequestStarted ->
                architecturePresenter().presentIndexedGraphRequestStarted(
                    event.view,
                    event.requestState,
                    event.statusMessage,
                )
            is GraphEditorApplicationEvent.IndexedGraphRequestFailed ->
                architecturePresenter().presentIndexedGraphRequestFailed(
                    event.view,
                    event.requestState,
                    event.statusMessage,
                )
            is GraphEditorApplicationEvent.ArchitectureGraphLoaded ->
                architecturePresenter().presentArchitectureGraph(event.view, event.requestState, event.statusMessage)
            is GraphEditorApplicationEvent.ClassDiagramLoaded ->
                architecturePresenter().presentClassDiagram(event.view, event.requestState, event.statusMessage)
            is GraphEditorApplicationEvent.ReviewGraphLoaded ->
                architecturePresenter().presentReviewGraph(event.view, event.requestState, event.statusMessage)
            is GraphEditorApplicationEvent.DebugGraphLoaded ->
                subjectPresenter().presentDebugGraphLoaded(
                    graph = event.graph,
                    source = event.source,
                    selectedMethodSignature = event.selectedMethodSignature,
                    summary = event.summary,
                )
            is GraphEditorApplicationEvent.ResourceNodeAdded ->
                subjectPresenter().presentResourceNodeAdded(event.selectedNodeId, event.statusMessage)
            is GraphEditorApplicationEvent.NavigationRequested ->
                sourceNavigationPresenter().presentNavigationRequested(event.nodeId)
            is GraphEditorApplicationEvent.NavigationStarting ->
                sourceNavigationPresenter().presentNavigationStarting(event.title)
            is GraphEditorApplicationEvent.NavigationOpened ->
                sourceNavigationPresenter().presentNavigationOpened(
                    nodeId = event.nodeId,
                    targetPath = event.targetPath,
                    line = event.line,
                    column = event.column,
                    title = event.title,
                )
            is GraphEditorApplicationEvent.NavigationNotFound ->
                sourceNavigationPresenter().presentNavigationNotFound(event.nodeId, event.label)
            is GraphEditorApplicationEvent.NavigationFailed ->
                sourceNavigationPresenter().presentNavigationFailed(
                    nodeId = event.nodeId,
                    message = event.message,
                    statusMessage = event.statusMessage,
                    level = event.level,
                )
            GraphEditorApplicationEvent.SettingsOpened ->
                sourceNavigationPresenter().presentSettingsOpened()
            is GraphEditorApplicationEvent.SettingsOpenFailed ->
                sourceNavigationPresenter().presentSettingsOpenFailed(event.message)
            is GraphEditorApplicationEvent.DraftPatchPreviewReady ->
                draftPatchPresenter().presentPreview(event.result)
            is GraphEditorApplicationEvent.DraftPatchApplied ->
                draftPatchPresenter().presentApply(event.result)
            is GraphEditorApplicationEvent.DraftPatchCleared ->
                draftPatchPresenter().presentClear(event.result)
            is GraphEditorApplicationEvent.DraftPatchRestored ->
                draftPatchPresenter().presentRestore(event.result)
            is GraphEditorApplicationEvent.DraftPatchUndone ->
                draftPatchPresenter().presentUndo(event.result)
            is GraphEditorApplicationEvent.DraftChangeConfirmed ->
                confirmedDraftPresenter().presentConfirmation(event.result, event.baseSnapshot)
            is GraphEditorApplicationEvent.DraftChangeUnconfirmed ->
                confirmedDraftPresenter().presentUnconfirmation(event.result, event.baseSnapshot)
            is GraphEditorApplicationEvent.GenerationPlanReady ->
                generationPresenter().presentGenerationPlan(event.result)
            is GraphEditorApplicationEvent.DraftAndCodeEligibilityUpdated ->
                generationPresenter().presentDraftAndCodeEligibility(
                    event.draftValidationState,
                    event.codeEligibilityDecision,
                )
            is GraphEditorApplicationEvent.GenerationRequestStarted ->
                generationPresenter().presentRequestStarted(event.result)
            is GraphEditorApplicationEvent.GenerationStreamingPreview ->
                generationPresenter().presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.GenerationPlanRequestFailed ->
                generationPresenter().presentGenerationPlanRequestFailure(event.result)
            is GraphEditorApplicationEvent.GenerationDiscussionReady ->
                generationPresenter().presentGenerationPlanDiscussion(event.result)
            is GraphEditorApplicationEvent.GenerationDiscussionFailed ->
                generationPresenter().presentGenerationPlanDiscussionFailure(event.result)
            is GraphEditorApplicationEvent.CodeDraftWriteReported ->
                generationPresenter().presentCodeDraftWriteReport(event.result)
            is GraphEditorApplicationEvent.GenerationFeedback ->
                generationPresenter().presentFeedback(event.level, event.message, event.preservePreviousStatusKind)
            is GraphEditorApplicationEvent.MergeWriteReported ->
                generationPresenter().presentMergeWriteReport(event.report, event.level, event.message)
            is GraphEditorApplicationEvent.GeneratedCodeDraftsReady ->
                generationPresenter().presentGeneratedCodeDrafts(event.result)
            is GraphEditorApplicationEvent.CodeDraftRequestFailed ->
                generationPresenter().presentCodeDraftRequestFailure(event.result)
            is GraphEditorApplicationEvent.QaCompleted ->
                reviewPresenter().presentQaCompleted(event.result)
            is GraphEditorApplicationEvent.ReviewRequestStarted ->
                reviewPresenter().presentRequestStarted(event.result)
            is GraphEditorApplicationEvent.ReviewStreamingPreview ->
                reviewPresenter().presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.QaFailed ->
                reviewPresenter().presentQaFailed(event.result)
            is GraphEditorApplicationEvent.DiffReviewCompleted ->
                reviewPresenter().presentDiffReviewCompleted(event.result)
            is GraphEditorApplicationEvent.DiffReviewFailed ->
                reviewPresenter().presentDiffReviewFailed(event.result)
            is GraphEditorApplicationEvent.BeautificationCompleted ->
                reviewPresenter().presentBeautificationCompleted(event.result)
            is GraphEditorApplicationEvent.BeautificationFailed ->
                reviewPresenter().presentBeautificationFailed(event.result)
        }
    }
}
