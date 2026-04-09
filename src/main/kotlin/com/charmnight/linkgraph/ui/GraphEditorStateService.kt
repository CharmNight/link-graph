package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.intellij.openapi.components.Service

/**
 * JCEF 图编辑器的项目级状态快照。
 * 后端动作只改这里，浏览器面板始终从快照重渲染，避免 bridge 与 UI 组件各自持有一份真相。
 */
@Service(Service.Level.PROJECT)
class GraphEditorStateService {
    /** 异步请求在前端展示时的生命周期阶段。 */
    enum class AsyncRequestPhase {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
    }

    /** 异步请求实际执行时采用的模式。 */
    enum class AsyncRequestExecutionMode {
        DISABLED,
        LOCAL_RULE,
        REMOTE_READY,
        REMOTE_FALLBACK,
    }

    /** 描述某类异步请求在 UI 中的状态。 */
    data class AsyncRequestState(
        /** 当前请求阶段。 */
        val phase: AsyncRequestPhase = AsyncRequestPhase.IDLE,
        /** 请求编号。 */
        val requestId: Long? = null,
        /** 请求场景名称。 */
        val scene: String? = null,
        /** 请求执行模式。 */
        val executionMode: AsyncRequestExecutionMode? = null,
        /** 面向用户展示的状态文案。 */
        val statusMessage: String? = null,
        /** 错误提示文案。 */
        val errorMessage: String? = null,
        /** 更详细的补充说明。 */
        val detailMessage: String? = null,
        /** 请求开始时间。 */
        val startedAtEpochMillis: Long? = null,
        /** 请求结束时间。 */
        val finishedAtEpochMillis: Long? = null,
        /** 是否处于流式输出。 */
        val streaming: Boolean = false,
        /** 是否发生了本地回退。 */
        val fallbackUsed: Boolean = false,
        /** 当前流式阶段。 */
        val streamPhase: String? = null,
        /** 当前已收到的流式预览文本。 */
        val previewText: String? = null,
        /** 最近一次预览刷新时间。 */
        val previewUpdatedAtEpochMillis: Long? = null,
        /** 是否正在把流式文本收敛为最终结构化结果。 */
        val finalizingStructuredResult: Boolean = false,
        /** 当前请求对应的 provider 标签。 */
        val providerLabel: String? = null,
        /** 当前请求对应的模型名称。 */
        val model: String? = null,
        /** endpoint 摘要。 */
        val endpointSummary: String? = null,
        /** 是否提供了提示词预览。 */
        val promptPreviewAvailable: Boolean = false,
    ) {
        companion object {
            /** 构造运行中的请求状态。 */
            fun running(
                requestId: Long? = null,
                scene: String? = null,
                executionMode: AsyncRequestExecutionMode? = null,
                statusMessage: String? = null,
                detailMessage: String? = null,
                startedAtEpochMillis: Long = System.currentTimeMillis(),
                streaming: Boolean = false,
                streamPhase: String? = null,
                previewText: String? = null,
                previewUpdatedAtEpochMillis: Long? = null,
                finalizingStructuredResult: Boolean = false,
                providerLabel: String? = null,
                model: String? = null,
                endpointSummary: String? = null,
                promptPreviewAvailable: Boolean = false,
            ) = AsyncRequestState(
                phase = AsyncRequestPhase.RUNNING,
                requestId = requestId,
                scene = scene,
                executionMode = executionMode,
                statusMessage = statusMessage,
                detailMessage = detailMessage,
                startedAtEpochMillis = startedAtEpochMillis,
                streaming = streaming,
                streamPhase = streamPhase,
                previewText = previewText,
                previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
                finalizingStructuredResult = finalizingStructuredResult,
                providerLabel = providerLabel,
                model = model,
                endpointSummary = endpointSummary,
                promptPreviewAvailable = promptPreviewAvailable,
            )

            /** 构造成功完成的请求状态。 */
            fun succeeded(
                requestId: Long? = null,
                scene: String? = null,
                executionMode: AsyncRequestExecutionMode? = null,
                statusMessage: String? = null,
                detailMessage: String? = null,
                startedAtEpochMillis: Long? = null,
                finishedAtEpochMillis: Long = System.currentTimeMillis(),
                streaming: Boolean = false,
                fallbackUsed: Boolean = false,
                streamPhase: String? = null,
                previewText: String? = null,
                previewUpdatedAtEpochMillis: Long? = null,
                finalizingStructuredResult: Boolean = false,
                providerLabel: String? = null,
                model: String? = null,
                endpointSummary: String? = null,
                promptPreviewAvailable: Boolean = false,
            ) = AsyncRequestState(
                phase = AsyncRequestPhase.SUCCEEDED,
                requestId = requestId,
                scene = scene,
                executionMode = executionMode,
                statusMessage = statusMessage,
                detailMessage = detailMessage,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                streaming = streaming,
                fallbackUsed = fallbackUsed,
                streamPhase = streamPhase,
                previewText = previewText,
                previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
                finalizingStructuredResult = finalizingStructuredResult,
                providerLabel = providerLabel,
                model = model,
                endpointSummary = endpointSummary,
                promptPreviewAvailable = promptPreviewAvailable,
            )

            /** 构造失败结束的请求状态。 */
            fun failed(
                message: String,
                requestId: Long? = null,
                scene: String? = null,
                executionMode: AsyncRequestExecutionMode? = null,
                detailMessage: String? = null,
                startedAtEpochMillis: Long? = null,
                finishedAtEpochMillis: Long = System.currentTimeMillis(),
                streaming: Boolean = false,
                streamPhase: String? = null,
                previewText: String? = null,
                previewUpdatedAtEpochMillis: Long? = null,
                finalizingStructuredResult: Boolean = false,
                providerLabel: String? = null,
                model: String? = null,
                endpointSummary: String? = null,
                promptPreviewAvailable: Boolean = false,
            ) = terminal(
                phase = AsyncRequestPhase.FAILED,
                message = message,
                requestId = requestId,
                scene = scene,
                executionMode = executionMode,
                detailMessage = detailMessage,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                streaming = streaming,
                streamPhase = streamPhase,
                previewText = previewText,
                previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
                finalizingStructuredResult = finalizingStructuredResult,
                providerLabel = providerLabel,
                model = model,
                endpointSummary = endpointSummary,
                promptPreviewAvailable = promptPreviewAvailable,
            )

            /** 构造超时结束的请求状态。 */
            fun timedOut(
                message: String,
                requestId: Long? = null,
                scene: String? = null,
                executionMode: AsyncRequestExecutionMode? = null,
                detailMessage: String? = null,
                startedAtEpochMillis: Long? = null,
                finishedAtEpochMillis: Long = System.currentTimeMillis(),
                streaming: Boolean = false,
                streamPhase: String? = null,
                previewText: String? = null,
                previewUpdatedAtEpochMillis: Long? = null,
                finalizingStructuredResult: Boolean = false,
                providerLabel: String? = null,
                model: String? = null,
                endpointSummary: String? = null,
                promptPreviewAvailable: Boolean = false,
            ) = terminal(
                phase = AsyncRequestPhase.TIMED_OUT,
                message = message,
                requestId = requestId,
                scene = scene,
                executionMode = executionMode,
                detailMessage = detailMessage,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                streaming = streaming,
                streamPhase = streamPhase,
                previewText = previewText,
                previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
                finalizingStructuredResult = finalizingStructuredResult,
                providerLabel = providerLabel,
                model = model,
                endpointSummary = endpointSummary,
                promptPreviewAvailable = promptPreviewAvailable,
            )

            /** 构造统一的终态请求状态。 */
            private fun terminal(
                phase: AsyncRequestPhase,
                message: String,
                requestId: Long?,
                scene: String?,
                executionMode: AsyncRequestExecutionMode?,
                detailMessage: String?,
                startedAtEpochMillis: Long?,
                finishedAtEpochMillis: Long,
                streaming: Boolean,
                streamPhase: String?,
                previewText: String?,
                previewUpdatedAtEpochMillis: Long?,
                finalizingStructuredResult: Boolean,
                providerLabel: String?,
                model: String?,
                endpointSummary: String?,
                promptPreviewAvailable: Boolean,
            ) = AsyncRequestState(
                phase = phase,
                requestId = requestId,
                scene = scene,
                executionMode = executionMode,
                statusMessage = message,
                errorMessage = message,
                detailMessage = detailMessage,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                streaming = streaming,
                streamPhase = streamPhase,
                previewText = previewText,
                previewUpdatedAtEpochMillis = previewUpdatedAtEpochMillis,
                finalizingStructuredResult = finalizingStructuredResult,
                providerLabel = providerLabel,
                model = model,
                endpointSummary = endpointSummary,
                promptPreviewAvailable = promptPreviewAvailable,
            )
        }
    }

    /** 记录补丁应用前的回退信息。 */
    data class DraftPatchUndoState(
        /** 应用补丁前的完整图。 */
        val graphBeforeApply: GraphDocument,
        /** 应用前正在预览的补丁。 */
        val patchPreview: GraphPatch? = null,
    )

    /** 状态读写锁对象。 */
    private val lock = Any()
    /** 当前项目的状态快照。 */
    private var state = Snapshot()

    /** 返回当前状态快照的副本。 */
    fun snapshot(): Snapshot = synchronized(lock) { state.copy() }

    /** 标记前端入口页面已经完成加载。 */
    fun markFrontendLoaded(entryUrl: String) {
        mutate {
            it.copy(
                frontendEntryUrl = entryUrl,
                lastMessageType = "frontendLoaded",
            )
        }
    }

    /** 用完整图初始化编辑器状态。 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        mutate { currentState ->
            /** 从图中提取的布局状态。 */
            val nextLayoutState = extractLayoutState(graph)
            /** 切图后应保留的选中节点。 */
            val nextSelectedNodeId = resolveSelectedNodeId(
                graph = graph,
                selectedNodeId = currentState.selectedNodeId,
                selectedMethodSignature = currentState.selectedMethodSignature,
            )
            /** 与当前图同步生成的三视图文档。 */
            val nextViewDocuments = buildViewDocuments(
                visibleGraph = graph,
                factFullGraph = graph,
                selectedNodeId = nextSelectedNodeId,
                selectedMethodSignature = currentState.selectedMethodSignature,
            )
            currentState.copy(
                visibleGraph = graph,
                workingGraph = graph,
                referenceFactGraph = graph,
                designBaselineGraph = null,
                factGraphView = nextViewDocuments.factGraphView,
                flowchartView = nextViewDocuments.flowchartView,
                resourceRelationView = nextViewDocuments.resourceRelationView,
                diff = null,
                diffMode = false,
                draftWorkbenchState = DraftWorkbenchState(),
                draftPatchPreview = null,
                draftPatchUndoState = null,
                lastDraftPatchApplyResult = null,
                auditResult = null,
                auditRequestState = AsyncRequestState(),
                diffReviewResult = null,
                diffReviewRequestState = AsyncRequestState(),
                graphBeautificationResult = null,
                graphBeautificationRequestState = AsyncRequestState(),
                importedMermaid = null,
                exportedMermaid = null,
                mermaidIssues = emptyList(),
                syncPreviewItems = emptyList(),
                syncPreviewRequested = false,
                generationPlan = null,
                generationPlanRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = AsyncRequestState(),
                workingGraphDirty = false,
                analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
                layoutState = nextLayoutState,
                semanticRevision = currentState.semanticRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                selectedNodeId = nextSelectedNodeId,
                lastGraphSource = source,
                lastMessageType = "loadGraph",
            )
        }
    }

    /** 加载投影视图，同时保留完整参考图。 */
    fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String? = null,
    ) {
        mutate { currentState ->
            /** 生效的方法签名，优先使用显式传入值。 */
            val effectiveSignature = selectedMethodSignature ?: currentState.selectedMethodSignature
            /** 从可见图中提取的布局状态。 */
            val nextLayoutState = extractLayoutState(visibleGraph)
            /** 投影切换后应保留的选中节点。 */
            val nextSelectedNodeId = resolveSelectedNodeId(
                graph = visibleGraph,
                selectedNodeId = currentState.selectedNodeId,
                selectedMethodSignature = effectiveSignature,
            )
            /** 与当前投影视图同步生成的三视图文档。 */
            val nextViewDocuments = buildViewDocuments(
                visibleGraph = visibleGraph,
                factFullGraph = fullGraph,
                selectedNodeId = nextSelectedNodeId,
                selectedMethodSignature = effectiveSignature,
            )
            currentState.copy(
                visibleGraph = visibleGraph,
                workingGraph = visibleGraph,
                referenceFactGraph = fullGraph,
                designBaselineGraph = null,
                factGraphView = nextViewDocuments.factGraphView,
                flowchartView = nextViewDocuments.flowchartView,
                resourceRelationView = nextViewDocuments.resourceRelationView,
                diff = null,
                diffMode = false,
                draftWorkbenchState = DraftWorkbenchState(),
                draftPatchPreview = null,
                draftPatchUndoState = null,
                lastDraftPatchApplyResult = null,
                auditResult = null,
                auditRequestState = AsyncRequestState(),
                diffReviewResult = null,
                diffReviewRequestState = AsyncRequestState(),
                graphBeautificationResult = null,
                graphBeautificationRequestState = AsyncRequestState(),
                importedMermaid = null,
                exportedMermaid = null,
                mermaidIssues = emptyList(),
                syncPreviewItems = emptyList(),
                syncPreviewRequested = false,
                generationPlan = null,
                generationPlanRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = AsyncRequestState(),
                workingGraphDirty = false,
                analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
                layoutState = nextLayoutState,
                semanticRevision = currentState.semanticRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                selectedMethodSignature = effectiveSignature,
                selectedNodeId = nextSelectedNodeId,
                lastGraphSource = source,
                lastMessageType = "loadGraph",
            )
        }
    }

    /** 加载语义分析结果并切换到对应视图。 */
    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) {
        mutate { currentState ->
            /** 从结果可见图中提取的布局状态。 */
            val nextLayoutState = extractLayoutState(outcome.visibleGraph)
            currentState.copy(
                visibleGraph = outcome.visibleGraph,
                workingGraph = outcome.visibleGraph,
                referenceFactGraph = outcome.fullGraph,
                designBaselineGraph = null,
                factGraphView = outcome.factGraphView,
                flowchartView = outcome.flowchartView,
                resourceRelationView = outcome.resourceRelationView,
                draftWorkbenchState = DraftWorkbenchState(),
                draftPatchPreview = null,
                draftPatchUndoState = null,
                lastDraftPatchApplyResult = null,
                auditResult = null,
                auditRequestState = AsyncRequestState(),
                diffReviewResult = null,
                diffReviewRequestState = AsyncRequestState(),
                graphBeautificationResult = null,
                graphBeautificationRequestState = AsyncRequestState(),
                diff = null,
                diffMode = false,
                importedMermaid = null,
                exportedMermaid = null,
                mermaidIssues = emptyList(),
                syncPreviewItems = emptyList(),
                generationPlan = null,
                generationPlanRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = AsyncRequestState(),
                sourceNavigationState = SourceNavigationState(),
                syncPreviewRequested = false,
                toolWindowOpenRequested = currentState.toolWindowOpenRequested,
                workingGraphDirty = false,
                analysisDisplayMode = outcome.displayMode,
                layoutState = nextLayoutState,
                semanticRevision = currentState.semanticRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                selectedMethodSignature = outcome.selectedMethodSignature,
                selectedNodeId = outcome.anchorNodeId ?: resolveSelectedNodeId(
                    graph = outcome.visibleGraph,
                    selectedNodeId = null,
                    selectedMethodSignature = outcome.selectedMethodSignature,
                ),
                lastGraphSource = source,
                operationFeedback = OperationFeedback(
                    level = outcome.feedbackLevel,
                    message = outcome.feedbackMessage,
                ),
                lastMessageType = "loadAnalysisOutcome",
            )
        }
    }

    /** 导入 Mermaid 文本及其解析结果。 */
    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<MermaidIssue> = emptyList(),
    ) {
        mutate { currentState: Snapshot ->
            /** 当前是否已经存在工作图。 */
            val hasWorkingGraph = currentState.workingGraph != null || currentState.visibleGraph != null
            /** 导入后用于继续编辑的工作图。 */
            val effectiveDraftGraph = if (hasWorkingGraph) {
                currentState.workingGraph ?: currentState.visibleGraph
            } else {
                graph
            }
            /** 导入后前端实际展示的图。 */
            val effectiveVisibleGraph = if (hasWorkingGraph) {
                currentState.visibleGraph ?: currentState.workingGraph
            } else {
                graph
            }
            /** 由导入后图形提取出的布局状态。 */
            val nextLayoutState = extractLayoutState(effectiveVisibleGraph ?: effectiveDraftGraph)
            /** 导入后前端仍应消费的三视图文档。 */
            val nextViewDocuments = buildViewDocuments(
                visibleGraph = effectiveVisibleGraph ?: effectiveDraftGraph,
                factFullGraph = currentState.referenceFactGraph ?: effectiveVisibleGraph ?: effectiveDraftGraph,
                selectedNodeId = currentState.selectedNodeId,
                selectedMethodSignature = currentState.selectedMethodSignature,
            )
            currentState.copy(
                visibleGraph = effectiveVisibleGraph,
                workingGraph = effectiveDraftGraph,
                designBaselineGraph = graph ?: currentState.designBaselineGraph,
                factGraphView = nextViewDocuments.factGraphView,
                flowchartView = nextViewDocuments.flowchartView,
                resourceRelationView = nextViewDocuments.resourceRelationView,
                diff = null,
                diffMode = false,
                draftWorkbenchState = DraftWorkbenchState(),
                draftPatchPreview = null,
                draftPatchUndoState = null,
                lastDraftPatchApplyResult = null,
                auditResult = null,
                auditRequestState = AsyncRequestState(),
                diffReviewResult = null,
                diffReviewRequestState = AsyncRequestState(),
                graphBeautificationResult = null,
                graphBeautificationRequestState = AsyncRequestState(),
                importedMermaid = mermaid,
                mermaidIssues = mermaidIssues,
                syncPreviewItems = emptyList(),
                syncPreviewRequested = false,
                generationPlan = null,
                generationPlanRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = AsyncRequestState(),
                workingGraphDirty = currentState.workingGraphDirty,
                layoutState = nextLayoutState,
                semanticRevision = currentState.semanticRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                lastMessageType = "importMermaid",
            )
        }
    }

    /** 标记 Mermaid 导出结果。 */
    fun markMermaidExported(mermaid: String) {
        mutate {
            it.copy(
                exportedMermaid = mermaid,
                lastMessageType = "exportMermaid",
            )
        }
    }

    /** 切换到 diff 视图并记录当前补丁。 */
    fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    ) {
        mutate { currentState ->
            /** 从 diff 图中提取的布局状态。 */
            val nextLayoutState = extractLayoutState(graph)
            /** diff 图切换后应保留的选中节点。 */
            val nextSelectedNodeId = resolveSelectedNodeId(
                graph = graph,
                selectedNodeId = currentState.selectedNodeId,
                selectedMethodSignature = currentState.selectedMethodSignature,
            )
            /** diff 模式下前端仍应拿到完整三视图文档。 */
            val nextViewDocuments = buildViewDocuments(
                visibleGraph = graph,
                factFullGraph = currentState.referenceFactGraph ?: graph,
                selectedNodeId = nextSelectedNodeId,
                selectedMethodSignature = currentState.selectedMethodSignature,
            )
            currentState.copy(
                visibleGraph = graph,
                factGraphView = nextViewDocuments.factGraphView,
                flowchartView = nextViewDocuments.flowchartView,
                resourceRelationView = nextViewDocuments.resourceRelationView,
                diff = diff,
                diffMode = true,
                draftWorkbenchState = DraftWorkbenchState(),
                draftPatchPreview = graph.patch,
                draftPatchUndoState = null,
                lastDraftPatchApplyResult = null,
                diffReviewResult = null,
                diffReviewRequestState = AsyncRequestState(),
                graphBeautificationResult = null,
                graphBeautificationRequestState = AsyncRequestState(),
                syncPreviewItems = emptyList(),
                syncPreviewRequested = false,
                generationPlan = null,
                generationPlanRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = AsyncRequestState(),
                workingGraphDirty = false,
                layoutState = nextLayoutState,
                semanticRevision = currentState.semanticRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                selectedNodeId = nextSelectedNodeId,
                lastMessageType = "showDiffMode",
            )
        }
    }

    /** 推入当前选中的方法签名，并尝试同步节点选中态。 */
    fun pushSelectedMethod(signature: String) {
        mutate { currentState ->
            currentState.copy(
                selectedMethodSignature = signature,
                selectedNodeId = findNodeIdBySignature(currentState.visibleGraph ?: currentState.workingGraph, signature) ?: currentState.selectedNodeId,
                lastMessageType = "selectedMethod",
            )
        }
    }

    /** 更新当前选中的节点。 */
    fun selectNode(nodeId: String) {
        mutate {
            it.copy(
                selectedNodeId = nodeId,
                lastMessageType = "nodeSelected",
            )
        }
    }

    /** 仅切换当前展示模式，复用现有三视图文档，不重新生成语义结果。 */
    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        mutate { currentState ->
            val nextVisibleGraph = resolveVisibleGraphForDisplayMode(currentState, displayMode)
            val nextSelectedNodeId = resolveSelectedNodeId(
                graph = nextVisibleGraph,
                selectedNodeId = currentState.selectedNodeId,
                selectedMethodSignature = currentState.selectedMethodSignature,
            )
            currentState.copy(
                analysisDisplayMode = displayMode,
                visibleGraph = nextVisibleGraph,
                workingGraph = nextVisibleGraph,
                layoutState = extractLayoutState(nextVisibleGraph),
                snapshotRevision = currentState.snapshotRevision + 1,
                selectedNodeId = nextSelectedNodeId,
                lastMessageType = "displayModeSwitch",
            )
        }
    }

    /** 标记图已发生变更，兼容旧调用入口。 */
    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
    ) = markWorkingGraphChanged(
        graph = graph,
        selectedMethodSignature = selectedMethodSignature,
        preserveDraftPatchUndo = preserveDraftPatchUndo,
    )

    /** 标记工作图已变化，并重置依赖于旧图的派生状态。 */
    fun markWorkingGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
    ) {
        mutate { currentState: Snapshot ->
            /** 生效的方法签名，优先使用显式传入值。 */
            val effectiveSignature = selectedMethodSignature ?: currentState.selectedMethodSignature
            /** 由新图提取出的布局状态。 */
            val nextLayoutState = extractLayoutState(graph)
            /** 图变更后应保留的选中节点。 */
            val nextSelectedNodeId = resolveSelectedNodeId(
                graph = graph,
                selectedNodeId = currentState.selectedNodeId,
                selectedMethodSignature = effectiveSignature,
            )
            /** 工作图变更后前端仍应消费的三视图文档。 */
            val nextViewDocuments = syncEditedViewDocuments(
                currentState = currentState,
                graph = graph,
                effectiveSignature = effectiveSignature,
                nextSelectedNodeId = nextSelectedNodeId,
            )
            currentState.copy(
                visibleGraph = graph,
                workingGraph = graph,
                factGraphView = nextViewDocuments.factGraphView,
                flowchartView = nextViewDocuments.flowchartView,
                resourceRelationView = nextViewDocuments.resourceRelationView,
                draftWorkbenchState = currentState.draftWorkbenchState,
                draftPatchPreview = null,
                draftPatchUndoState = if (preserveDraftPatchUndo) currentState.draftPatchUndoState else null,
                lastDraftPatchApplyResult = null,
                auditResult = null,
                auditRequestState = AsyncRequestState(),
                diffReviewResult = null,
                diffReviewRequestState = AsyncRequestState(),
                syncPreviewItems = emptyList(),
                syncPreviewRequested = false,
                generationPlan = null,
                generationPlanRequestState = AsyncRequestState(),
                graphBeautificationResult = null,
                graphBeautificationRequestState = AsyncRequestState(),
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = AsyncRequestState(),
                workingGraphDirty = true,
                layoutState = nextLayoutState,
                semanticRevision = currentState.semanticRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                selectedMethodSignature = effectiveSignature,
                selectedNodeId = nextSelectedNodeId,
                lastMessageType = "graphChanged",
            )
        }
    }

    /** 合并前端上报的布局坐标变化。 */
    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        mutate { currentState ->
            currentState.copy(
                layoutState = currentState.layoutState.copy(
                    positions = currentState.layoutState.positions + positions,
                ),
                layoutRevision = currentState.layoutRevision + 1,
                snapshotRevision = currentState.snapshotRevision + 1,
                lastMessageType = "layoutChanged",
            )
        }
    }

    /** 开始一次源码跳转请求。 */
    fun requestSourceNavigation(nodeId: String) {
        mutate {
            it.copy(
                sourceNavigationState = SourceNavigationState(
                    nodeId = nodeId,
                    phase = SourceNavigationPhase.RUNNING,
                ),
                lastMessageType = "requestSourceNavigation",
            )
        }
    }

    /** 标记源码跳转已成功打开目标位置。 */
    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    ) {
        mutate {
            it.copy(
                sourceNavigationState = SourceNavigationState(
                    nodeId = nodeId,
                    phase = SourceNavigationPhase.SUCCEEDED,
                    result = SourceNavigationResult.OPENED,
                    targetPath = targetPath,
                    line = line,
                    column = column,
                ),
                lastMessageType = "sourceNavigationSucceeded",
            )
        }
    }

    /** 标记源码跳转未找到目标。 */
    fun markSourceNavigationNotFound(nodeId: String) {
        mutate {
            it.copy(
                sourceNavigationState = SourceNavigationState(
                    nodeId = nodeId,
                    phase = SourceNavigationPhase.NOT_FOUND,
                ),
                lastMessageType = "sourceNavigationNotFound",
            )
        }
    }

    /** 标记源码跳转执行失败。 */
    fun markSourceNavigationFailed(
        nodeId: String,
        errorMessage: String,
    ) {
        mutate {
            it.copy(
                sourceNavigationState = SourceNavigationState(
                    nodeId = nodeId,
                    phase = SourceNavigationPhase.FAILED,
                    errorMessage = errorMessage,
                ),
                lastMessageType = "sourceNavigationFailed",
            )
        }
    }

    /** 记录当前正在预览的草稿补丁。 */
    fun markDraftPatchPreview(patch: GraphPatch) {
        mutate {
            it.copy(
                draftPatchPreview = patch,
                lastMessageType = "draftPatchPreview",
            )
        }
    }

    /** 记录统一草稿层状态。 */
    fun markDraftWorkbenchState(state: DraftWorkbenchState) {
        mutate {
            it.copy(
                draftWorkbenchState = state,
                lastMessageType = "draftWorkbenchState",
            )
        }
    }

    /** 清除当前草稿补丁预览。 */
    fun clearDraftPatchPreview() {
        mutate {
            it.copy(
                draftPatchPreview = null,
                lastMessageType = "draftPatchCleared",
            )
        }
    }

    /** 记录草稿补丁应用前的回退信息。 */
    fun markDraftPatchApplyUndo(
        graphBeforeApply: GraphDocument,
        patchPreview: GraphPatch?,
    ) {
        mutate {
            it.copy(
                draftPatchUndoState = DraftPatchUndoState(
                    graphBeforeApply = graphBeforeApply,
                    patchPreview = patchPreview,
                ),
                lastMessageType = "draftPatchApplyUndoReady",
            )
        }
    }

    /** 清除草稿补丁应用回退状态。 */
    fun clearDraftPatchApplyUndo() {
        mutate {
            it.copy(
                draftPatchUndoState = null,
                lastMessageType = "draftPatchApplyUndoCleared",
            )
        }
    }

    /** 记录最近一次草稿补丁应用结果。 */
    fun markDraftPatchApplyResult(result: DraftPatchApplyResult) {
        mutate {
            it.copy(
                lastDraftPatchApplyResult = result,
                lastMessageType = "draftPatchApplied",
            )
        }
    }

    /** 写入审计结果及对应请求状态。 */
    fun markAuditResult(
        result: GraphPatchResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                auditResult = result,
                auditRequestState = requestState,
                lastMessageType = "auditResult",
            )
        }
    }

    /** 标记审计请求开始执行。 */
    fun beginAuditRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                auditResult = null,
                auditRequestState = requestState,
                lastMessageType = "requestAudit",
            )
        }
    }

    /** 标记审计请求失败。 */
    fun markAuditRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                auditResult = null,
                auditRequestState = requestState,
                lastMessageType = "requestAudit",
            )
        }
    }

    /** 更新审计请求的流式预览。 */
    fun updateAuditRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.auditRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                auditRequestState = nextRequestState,
                lastMessageType = "requestAudit",
            )
        }
    }

    /** 写入 diff 审核结果及对应请求状态。 */
    fun markDiffReviewResult(
        result: GraphPatchResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                diffReviewResult = result,
                diffReviewRequestState = requestState,
                lastMessageType = "diffReviewResult",
            )
        }
    }

    /** 标记 diff 审核请求开始执行。 */
    fun beginDiffReviewRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    /** 标记 diff 审核请求失败。 */
    fun markDiffReviewRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                diffReviewResult = null,
                diffReviewRequestState = requestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    /** 更新差异分析请求的流式预览。 */
    fun updateDiffReviewRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.diffReviewRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                diffReviewRequestState = nextRequestState,
                lastMessageType = "requestDiffReview",
            )
        }
    }

    /** 写入链路讲解结果及对应请求状态。 */
    fun markGraphBeautificationResult(
        result: GraphBeautificationResult,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                graphBeautificationResult = result,
                graphBeautificationRequestState = requestState,
                lastMessageType = "graphBeautificationResult",
            )
        }
    }

    /** 标记链路讲解请求开始执行。 */
    fun beginGraphBeautificationRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                lastMessageType = "requestGraphBeautification",
            )
        }
    }

    /** 标记链路讲解请求失败。 */
    fun markGraphBeautificationRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                graphBeautificationResult = null,
                graphBeautificationRequestState = requestState,
                lastMessageType = "requestGraphBeautification",
            )
        }
    }

    /** 更新链路讲解请求的流式预览。 */
    fun updateGraphBeautificationRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.graphBeautificationRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                graphBeautificationRequestState = nextRequestState,
                lastMessageType = "requestGraphBeautification",
            )
        }
    }

    /** 记录同步预览项并标记前端已请求展示。 */
    fun requestSyncPreview(items: List<SyncPreviewItem>) {
        mutate {
            it.copy(
                syncPreviewItems = items,
                syncPreviewRequested = true,
                lastMessageType = "requestSyncPreview",
            )
        }
    }

    /** 写入生成计划及对应请求状态。 */
    fun markGenerationPlan(
        plan: GenerationPlan,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                generationPlan = plan,
                generationPlanRequestState = requestState,
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    /** 标记生成计划请求开始执行。 */
    fun beginGenerationPlanRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                generationPlan = null,
                generationPlanRequestState = requestState,
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    /** 标记生成计划请求失败。 */
    fun markGenerationPlanRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                generationPlan = null,
                generationPlanRequestState = requestState,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    /** 更新实现计划请求的流式预览。 */
    fun updateGenerationPlanRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.generationPlanRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                generationPlanRequestState = nextRequestState,
                lastMessageType = "requestGenerationPlan",
            )
        }
    }

    /** 写入代码草稿结果及对应请求状态。 */
    fun markGeneratedCodeDrafts(
        drafts: List<GeneratedCodeDraft>,
        warnings: List<String>,
        source: LlmResultSource,
        promptPreview: String?,
        requestState: AsyncRequestState = AsyncRequestState.succeeded(),
    ) {
        mutate {
            it.copy(
                generatedCodeDrafts = drafts,
                generatedCodeDraftWarnings = warnings,
                generatedCodeDraftSource = source,
                generatedCodeDraftPromptPreview = promptPreview,
                codeDraftRequestState = requestState,
                generatedCodeDraftWriteReport = null,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    /** 标记代码草稿请求开始执行。 */
    fun beginCodeDraftRequest(requestState: AsyncRequestState = AsyncRequestState.running()) {
        mutate {
            it.copy(
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = requestState,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    /** 标记代码草稿请求失败。 */
    fun markCodeDraftRequestFailed(
        message: String,
        requestState: AsyncRequestState = AsyncRequestState.failed(message),
    ) {
        mutate {
            it.copy(
                generatedCodeDrafts = emptyList(),
                generatedCodeDraftWarnings = emptyList(),
                generatedCodeDraftSource = null,
                generatedCodeDraftPromptPreview = null,
                generatedCodeDraftWriteReport = null,
                codeDraftRequestState = requestState,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    /** 更新代码草稿请求的流式预览。 */
    fun updateCodeDraftRequestPreview(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean = false,
    ) {
        mutate { currentState ->
            val nextRequestState = currentState.codeDraftRequestState.updatedPreviewOrNull(
                requestId = requestId,
                previewText = previewText,
                finalizingStructuredResult = finalizingStructuredResult,
            ) ?: return@mutate currentState
            currentState.copy(
                codeDraftRequestState = nextRequestState,
                lastMessageType = "requestCodeDrafts",
            )
        }
    }

    /** 记录代码草稿写入项目目录后的报告。 */
    fun markGeneratedCodeDraftWriteReport(report: GeneratedCodeDraftWriteReport) {
        mutate {
            it.copy(
                generatedCodeDraftWriteReport = report,
                lastMessageType = "applyCodeDrafts",
            )
        }
    }

    /** 标记工具窗口已被打开。 */
    fun markToolWindowOpened() {
        mutate {
            it.copy(
                toolWindowOpenRequested = true,
                lastMessageType = "toolWindowOpened",
            )
        }
    }

    /** 写入最近一次操作反馈信息。 */
    fun markOperationFeedback(
        level: OperationFeedbackLevel,
        message: String,
    ) {
        mutate {
            it.copy(
                operationFeedback = OperationFeedback(level = level, message = message),
                lastMessageType = "operationFeedback",
            )
        }
    }

    /** 仅更新最后一条消息类型。 */
    fun markLastMessageType(messageType: String) {
        mutate {
            it.copy(lastMessageType = messageType)
        }
    }

    /** 统一执行状态变更，并在必要时推进快照版本号。 */
    private fun mutate(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            /** 变更前的状态快照。 */
            val currentState = state
            /** 变更后的候选状态。 */
            val nextState = transform(currentState)
            state = when {
                nextState == currentState -> currentState
                nextState.snapshotRevision != currentState.snapshotRevision -> nextState
                else ->
                    // snapshotRevision 是前后端快照分发的统一版本号。
                    // 只要前端可见状态发生变化，即使不是图语义/布局变化，也必须推进版本，
                    // 否则 transport 会把这次更新当成旧快照吞掉。
                    nextState.copy(snapshotRevision = currentState.snapshotRevision + 1)
            }
        }
    }

    /** 在请求仍处于运行态时合并最新的流式预览。 */
    private fun AsyncRequestState.updatedPreviewOrNull(
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean,
    ): AsyncRequestState? {
        if (this.requestId != requestId || phase != AsyncRequestPhase.RUNNING) {
            return null
        }
        return copy(
            streaming = true,
            streamPhase = if (finalizingStructuredResult) "FINALIZING" else "STREAMING",
            previewText = previewText,
            previewUpdatedAtEpochMillis = System.currentTimeMillis(),
            finalizingStructuredResult = finalizingStructuredResult,
        )
    }

    data class Snapshot(
        /** 当前前端展示的图。 */
        val visibleGraph: GraphDocument? = null,
        /** 当前可编辑的工作图。 */
        val workingGraph: GraphDocument? = null,
        /** 事实图参考基线。 */
        val referenceFactGraph: GraphDocument? = null,
        /** 设计导入基线图。 */
        val designBaselineGraph: GraphDocument? = null,
        /** 事实链路视图专用文档。 */
        val factGraphView: FactGraphViewDocument? = null,
        /** 流程图视图专用文档。 */
        val flowchartView: FlowchartViewDocument? = null,
        /** 资源关系视图专用文档。 */
        val resourceRelationView: ResourceRelationViewDocument? = null,
        /** 当前分析展示模式。 */
        val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        /** 统一草稿层状态。 */
        val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
        /** 当前预览中的草稿补丁。 */
        val draftPatchPreview: GraphPatch? = null,
        /** 草稿补丁应用回退信息。 */
        val draftPatchUndoState: DraftPatchUndoState? = null,
        /** 最近一次草稿补丁应用结果。 */
        val lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
        /** 图审计结果。 */
        val auditResult: GraphPatchResult? = null,
        /** 图审计请求状态。 */
        val auditRequestState: AsyncRequestState = AsyncRequestState(),
        /** diff 审核结果。 */
        val diffReviewResult: GraphPatchResult? = null,
        /** diff 审核请求状态。 */
        val diffReviewRequestState: AsyncRequestState = AsyncRequestState(),
        /** 链路讲解结果。 */
        val graphBeautificationResult: GraphBeautificationResult? = null,
        /** 链路讲解请求状态。 */
        val graphBeautificationRequestState: AsyncRequestState = AsyncRequestState(),
        /** 当前 diff 结果。 */
        val diff: GraphDiff? = null,
        /** 是否处于 diff 模式。 */
        val diffMode: Boolean = false,
        /** 最近一次加载图的来源标识。 */
        val lastGraphSource: String? = null,
        /** 前端入口地址。 */
        val frontendEntryUrl: String? = null,
        /** 当前选中的方法签名。 */
        val selectedMethodSignature: String? = null,
        /** 当前选中的节点 ID。 */
        val selectedNodeId: String? = null,
        /** 最近一次导入的 Mermaid 文本。 */
        val importedMermaid: String? = null,
        /** 最近一次导出的 Mermaid 文本。 */
        val exportedMermaid: String? = null,
        /** Mermaid 解析问题列表。 */
        val mermaidIssues: List<MermaidIssue> = emptyList(),
        /** 同步预览项列表。 */
        val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
        /** 代码生成计划。 */
        val generationPlan: GenerationPlan? = null,
        /** 代码生成计划请求状态。 */
        val generationPlanRequestState: AsyncRequestState = AsyncRequestState(),
        /** 已生成的代码草稿列表。 */
        val generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
        /** 代码草稿警告列表。 */
        val generatedCodeDraftWarnings: List<String> = emptyList(),
        /** 代码草稿结果来源。 */
        val generatedCodeDraftSource: LlmResultSource? = null,
        /** 代码草稿提示词预览。 */
        val generatedCodeDraftPromptPreview: String? = null,
        /** 代码草稿写入报告。 */
        val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
        /** 代码草稿请求状态。 */
        val codeDraftRequestState: AsyncRequestState = AsyncRequestState(),
        /** 源码跳转状态。 */
        val sourceNavigationState: SourceNavigationState = SourceNavigationState(),
        /** 是否已经请求过同步预览。 */
        val syncPreviewRequested: Boolean = false,
        /** 是否需要打开工具窗口。 */
        val toolWindowOpenRequested: Boolean = false,
        /** 工作图是否有未保存修改。 */
        val workingGraphDirty: Boolean = false,
        /** 当前布局状态。 */
        val layoutState: GraphLayoutState = GraphLayoutState(),
        /** 语义内容版本号。 */
        val semanticRevision: Long = 0,
        /** 布局版本号。 */
        val layoutRevision: Long = 0,
        /** 快照分发版本号。 */
        val snapshotRevision: Long = 0,
        /** 最近一次操作反馈。 */
        val operationFeedback: OperationFeedback? = null,
        /** 最近一次状态消息类型。 */
        val lastMessageType: String? = null,
    )

    /** 源码跳转操作在前端展示的状态。 */
    data class SourceNavigationState(
        /** 发起跳转的节点 ID。 */
        val nodeId: String? = null,
        /** 当前跳转阶段。 */
        val phase: SourceNavigationPhase = SourceNavigationPhase.IDLE,
        /** 成功后的结果类型。 */
        val result: SourceNavigationResult? = null,
        /** 打开的目标路径。 */
        val targetPath: String? = null,
        /** 打开的行号。 */
        val line: Int? = null,
        /** 打开的列号。 */
        val column: Int? = null,
        /** 失败时的错误信息。 */
        val errorMessage: String? = null,
    )

    /** 源码跳转请求的生命周期阶段。 */
    enum class SourceNavigationPhase {
        IDLE,
        RUNNING,
        SUCCEEDED,
        NOT_FOUND,
        FAILED,
    }

    /** 源码跳转成功后的结果类型。 */
    enum class SourceNavigationResult {
        OPENED,
    }

    /** 面向前端展示的一次操作反馈。 */
    data class OperationFeedback(
        /** 反馈级别。 */
        val level: OperationFeedbackLevel,
        /** 反馈文案。 */
        val message: String,
    )

    /** 操作反馈级别。 */
    enum class OperationFeedbackLevel {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
    }
}
