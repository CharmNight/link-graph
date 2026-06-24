package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState

/** 加载完整图数据，刷新工作区与语义基线，并将场景切换到事实图视图。 */
internal fun GraphEditorStateSnapshot.withLoadedGraph(
    graph: GraphDocument,
    source: String,
): GraphEditorStateSnapshot {
    val selectedNodeId = resolveSelectedNodeId(
        graph = graph,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = graph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextScene = AnalysisDisplayMode.FACT_GRAPH.toWorkspaceSceneId()
    return resetDerivedGraphState(
        preserveDrafts = false,
        preserveWorkbenchPlan = false,
    ).copy(
        semanticFactGraph = graph,
        workspaceBaseGraph = graph,
        workspaceGraph = graph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(graph),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(
            nextScene,
            GraphSceneState(
                selectedNodeId = selectedNodeId,
                anchorNodeId = selectedNodeId ?: graph.nodes.firstOrNull()?.id,
                layoutState = extractLayoutState(graph),
            ),
        ),
        workingGraphDirty = false,
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = selectedMethodSignature,
        lastGraphSource = source,
        lastMessageType = "loadGraph",
    ).withAssistantContextFromCurrentState()
}

/** 装载一个带有"可见子图 + 全量底图"两层结构的图，使事实图视图聚焦可见子图但其他视图仍可基于全量底图。 */
internal fun GraphEditorStateSnapshot.withLoadedGraphProjection(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    source: String,
    selectedMethodSignatureOverride: String? = null,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val selectedNodeId = resolveSelectedNodeId(
        graph = visibleGraph,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = fullGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextScene = AnalysisDisplayMode.FACT_GRAPH.toWorkspaceSceneId()
    return resetDerivedGraphState(
        preserveDrafts = false,
        preserveWorkbenchPlan = false,
    ).copy(
        semanticFactGraph = fullGraph,
        workspaceBaseGraph = fullGraph,
        workspaceGraph = fullGraph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(fullGraph, visibleGraph),
        factGraphView = nextViewDocuments.factGraphView.copy(
            visibleGraph = visibleGraph,
            anchorNodeId = selectedNodeId,
        ),
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(
            nextScene,
            GraphSceneState(
                selectedNodeId = selectedNodeId,
                anchorNodeId = selectedNodeId ?: visibleGraph.nodes.firstOrNull()?.id,
                layoutState = extractLayoutState(visibleGraph),
            ),
        ),
        workingGraphDirty = false,
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        lastGraphSource = source,
        lastMessageType = "loadGraph",
    ).withAssistantContextFromCurrentState()
}

/** 接收一次完整的语义分析结果，按展示模式重算工作区、视图与场景，并尽量保留之前已确认的草稿变更。 */
internal fun GraphEditorStateSnapshot.withLoadedAnalysisOutcome(
    outcome: AnalysisOutcome,
    source: String,
    graphPatchApplyService: GraphPatchApplyService,
    runtimeTrace: ((() -> String) -> Unit)? = null,
): GraphEditorStateSnapshot {
    val nextSemanticFactGraph = outcome.factGraphView?.fullGraph ?: outcome.fullGraph
    val nextWorkspaceBaseGraph = when (outcome.displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> nextSemanticFactGraph
        AnalysisDisplayMode.FLOWCHART -> outcome.flowchartView?.fullGraph ?: outcome.fullGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> outcome.resourceRelationView?.fullGraph ?: outcome.fullGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH,
        AnalysisDisplayMode.CLASS_DIAGRAM,
        AnalysisDisplayMode.REVIEW_GRAPH,
        -> nextSemanticFactGraph
    }
    val preservedDraftState = preservedConfirmedDraftState(this, outcome.selectedMethodSignature)
    val hasPreservedDrafts = preservedDraftState.draftChanges.isNotEmpty()
    val nextWorkspaceGraph = if (hasPreservedDrafts) {
        reapplyConfirmedDraftGraph(nextWorkspaceBaseGraph, preservedDraftState, graphPatchApplyService)
    } else {
        nextWorkspaceBaseGraph
    }
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = nextWorkspaceGraph,
        selectedNodeId = outcome.anchorNodeId,
        selectedMethodSignature = outcome.selectedMethodSignature,
        runtimeTrace = runtimeTrace,
    )
    val nextScene = outcome.displayMode.toWorkspaceSceneId()
    val nextVisibleGraph = when (outcome.displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
        AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        AnalysisDisplayMode.CLASS_DIAGRAM -> classDiagramView.visibleGraph
        AnalysisDisplayMode.REVIEW_GRAPH -> reviewGraphView.visibleGraph
    }
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = outcome.anchorNodeId,
        selectedMethodSignature = outcome.selectedMethodSignature,
    )
    return resetDerivedGraphState(
        preserveDrafts = hasPreservedDrafts,
        preserveWorkbenchPlan = false,
    ).copy(
        semanticFactGraph = nextSemanticFactGraph,
        workspaceBaseGraph = nextWorkspaceBaseGraph,
        workspaceGraph = nextWorkspaceGraph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            nextWorkspaceGraph,
            nextSemanticFactGraph,
            outcome.flowchartView?.fullGraph,
            outcome.resourceRelationView?.fullGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftWorkbenchState = preservedDraftState,
        draftVersion = if (hasPreservedDrafts) draftVersion else 0,
        workingGraphDirty = hasPreservedDrafts,
        analysisDisplayMode = outcome.displayMode,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(
            nextScene,
            GraphSceneState(
                selectedNodeId = nextSelectedNodeId,
                anchorNodeId = nextSelectedNodeId ?: nextVisibleGraph.nodes.firstOrNull()?.id,
                layoutState = extractLayoutState(nextVisibleGraph),
            ),
        ),
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = outcome.selectedMethodSignature,
        lastGraphSource = source,
        operationFeedback = OperationFeedback(
            level = outcome.feedbackLevel,
            message = outcome.statusMessage,
        ),
        lastMessageType = "loadAnalysisOutcome",
    ).withAssistantContextFromCurrentState()
}

/** 切换展示模式（事实图/流程图/资源关系图等），并同步更新当前场景和锚点选中节点。 */
internal fun GraphEditorStateSnapshot.withSwitchedAnalysisDisplayMode(
    displayMode: AnalysisDisplayMode,
): GraphEditorStateSnapshot {
    val nextScene = displayMode.toWorkspaceSceneId()
    val nextVisibleGraph = resolveVisibleGraphForDisplayMode(this, displayMode)
    val currentSceneState = sceneState(nextScene)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = nextSelectedNodeId,
        anchorNodeId = currentSceneState.anchorNodeId
            ?.takeIf { anchorNodeId -> nextVisibleGraph.nodes.any { it.id == anchorNodeId } }
            ?: nextSelectedNodeId
            ?: nextVisibleGraph.nodes.firstOrNull()?.id,
    )
    return copy(
        analysisDisplayMode = displayMode,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "displayModeSwitch",
    ).withAssistantContextFromCurrentState()
}

/** 在发起某类索引图（架构/类图/评审）请求时，清除该视图旧数据并切到对应场景，给出"加载中"提示。 */
internal fun GraphEditorStateSnapshot.withIndexedGraphRequestStarted(
    view: IndexedGraphView,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    val displayMode = view.toAnalysisDisplayMode()
    val nextScene = displayMode.toWorkspaceSceneId()
    val clearedState = clearIndexedGraphView(view)
    val nextVisibleGraph = resolveVisibleGraphForDisplayMode(clearedState, displayMode)
    val currentSceneState = sceneState(nextScene)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = nextSelectedNodeId,
        anchorNodeId = currentSceneState.anchorNodeId
            ?.takeIf { anchorNodeId -> nextVisibleGraph.nodes.any { it.id == anchorNodeId } }
            ?: nextSelectedNodeId
            ?: nextVisibleGraph.nodes.firstOrNull()?.id,
    )
    return clearedState.copy(
        indexedGraphRequestStates = clearedState.indexedGraphRequestStates + (view to requestState),
        analysisDisplayMode = displayMode,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = clearedState.sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = ApplicationFeedbackLevel.INFO,
            message = statusMessage,
        ),
        snapshotRevision = clearedState.snapshotRevision + 1,
        lastMessageType = "indexedGraphRequestStarted",
    )
}

/** 当某类索引图请求失败时，依据请求 ID 判断是否仍为当前请求，匹配则把错误反馈写入快照。 */
internal fun GraphEditorStateSnapshot.withIndexedGraphRequestFailed(
    view: IndexedGraphView,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(view, requestState)) {
        return this
    }
    return copy(
        indexedGraphRequestStates = indexedGraphRequestStates + (view to requestState),
        operationFeedback = OperationFeedback(
            level = ApplicationFeedbackLevel.ERROR,
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "indexedGraphRequestFailed",
    )
}

/** 当工作区图被外部修改或被工具自身改动后，重算视图、布局、选中节点并保留必要的草稿状态。 */
internal fun GraphEditorStateSnapshot.withWorkspaceGraphChanged(
    graph: GraphDocument,
    selectedMethodSignatureOverride: String? = null,
    preserveDraftPatchUndo: Boolean = false,
    workingGraphDirtyOverride: Boolean = true,
    graphEditTransaction: GraphEditTransaction? = null,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = graph,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextVisibleGraph = when (analysisDisplayMode) {
        AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        AnalysisDisplayMode.CLASS_DIAGRAM -> classDiagramView.visibleGraph
        AnalysisDisplayMode.REVIEW_GRAPH -> reviewGraphView.visibleGraph
    }
    val sceneState = currentSceneState()
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = sceneState.selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextLayoutState = mergeLayoutState(
        graph = nextVisibleGraph,
        preferred = sceneState.layoutState,
        fallback = extractLayoutState(nextVisibleGraph),
    )
    val nextSceneState = sceneState.copy(
        selectedNodeId = nextSelectedNodeId,
        anchorNodeId = nextSelectedNodeId ?: nextVisibleGraph.nodes.firstOrNull()?.id,
        layoutState = nextLayoutState,
    )
    return resetDerivedGraphState(
        preserveDrafts = true,
        preserveWorkbenchPlan = true,
    ).copy(
        workspaceGraph = graph,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            graph,
            semanticFactGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftPatchUndoState = if (preserveDraftPatchUndo) draftPatchUndoState else null,
        workingGraphDirty = workingGraphDirtyOverride,
        lastGraphEditTransaction = graphEditTransaction,
        lastGraphEditRejection = null,
        sceneStates = sceneStates.withSceneState(currentSceneId, nextSceneState),
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        lastMessageType = "workspaceGraphChanged",
    )
}

/** 当一次图编辑请求被校验/拒绝时，把首条拒绝原因以错误级别反馈写入快照，并清空挂起事务。 */
internal fun GraphEditorStateSnapshot.withGraphEditRejected(
    rejection: GraphEditRejected,
): GraphEditorStateSnapshot {
    val firstIssue = rejection.issues.firstOrNull()
    val message = firstIssue?.let { issue ->
        "图编辑失败：${issue.code.name} - ${issue.message}"
    } ?: "图编辑失败。"
    return copy(
        operationFeedback = OperationFeedback(
            level = ApplicationFeedbackLevel.ERROR,
            message = message,
        ),
        lastGraphEditRejection = rejection,
        lastGraphEditTransaction = null,
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "graphEditRejected",
    )
}

/** 装载架构图结果，切换到架构图场景，更新索引请求状态、导航索引和操作反馈。 */
internal fun GraphEditorStateSnapshot.withLoadedArchitectureGraphView(
    view: ArchitectureGraphResult,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(IndexedGraphView.ARCHITECTURE, requestState)) {
        return this
    }
    val nextScene = AnalysisDisplayMode.ARCHITECTURE_GRAPH.toWorkspaceSceneId()
    val currentSceneState = sceneState(nextScene)
    val authoritativeAnchorNodeId = view.anchorNodeId
        ?.takeIf { anchorNodeId -> view.visibleGraph.nodes.any { it.id == anchorNodeId } }
    val selectedNodeId = resolveSelectedNodeId(
        graph = view.visibleGraph,
        selectedNodeId = authoritativeAnchorNodeId ?: currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = selectedNodeId,
        anchorNodeId = authoritativeAnchorNodeId ?: selectedNodeId ?: view.visibleGraph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(view.visibleGraph),
    )
    return copy(
        architectureGraphView = view,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            workspaceGraph,
            semanticFactGraph,
            view.visibleGraph,
            view.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        analysisDisplayMode = AnalysisDisplayMode.ARCHITECTURE_GRAPH,
        indexedGraphRequestStates = indexedGraphRequestStates + (IndexedGraphView.ARCHITECTURE to requestState),
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = requestState.toApplicationFeedbackLevel(),
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "loadArchitectureGraph",
    )
}

/** 装载类图结果，切换到类图场景，并维护跨视图的导航索引和反馈。 */
internal fun GraphEditorStateSnapshot.withLoadedClassDiagramView(
    view: ClassDiagramResult,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(IndexedGraphView.CLASS_DIAGRAM, requestState)) {
        return this
    }
    val nextScene = AnalysisDisplayMode.CLASS_DIAGRAM.toWorkspaceSceneId()
    val currentSceneState = sceneState(nextScene)
    val authoritativeAnchorNodeId = view.anchorNodeId
        ?.takeIf { anchorNodeId -> view.visibleGraph.nodes.any { it.id == anchorNodeId } }
    val selectedNodeId = resolveSelectedNodeId(
        graph = view.visibleGraph,
        selectedNodeId = authoritativeAnchorNodeId ?: currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = selectedNodeId,
        anchorNodeId = authoritativeAnchorNodeId ?: selectedNodeId ?: view.visibleGraph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(view.visibleGraph),
    )
    return copy(
        classDiagramView = view,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            workspaceGraph,
            semanticFactGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            view.visibleGraph,
            view.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        analysisDisplayMode = AnalysisDisplayMode.CLASS_DIAGRAM,
        indexedGraphRequestStates = indexedGraphRequestStates + (IndexedGraphView.CLASS_DIAGRAM to requestState),
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = requestState.toApplicationFeedbackLevel(),
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "loadClassDiagram",
    )
}

/** 装载评审图结果，切换到评审图场景，并刷新跨视图的导航索引和反馈。 */
internal fun GraphEditorStateSnapshot.withLoadedReviewGraphView(
    view: ReviewGraphResult,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(IndexedGraphView.REVIEW, requestState)) {
        return this
    }
    val nextScene = AnalysisDisplayMode.REVIEW_GRAPH.toWorkspaceSceneId()
    val currentSceneState = sceneState(nextScene)
    val authoritativeAnchorNodeId = view.anchorNodeId
        ?.takeIf { anchorNodeId -> view.visibleGraph.nodes.any { it.id == anchorNodeId } }
    val selectedNodeId = resolveSelectedNodeId(
        graph = view.visibleGraph,
        selectedNodeId = authoritativeAnchorNodeId ?: currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = selectedNodeId,
        anchorNodeId = authoritativeAnchorNodeId ?: selectedNodeId ?: view.visibleGraph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(view.visibleGraph),
    )
    return copy(
        reviewGraphView = view,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            workspaceGraph,
            semanticFactGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            view.visibleGraph,
            view.fullGraph,
        ),
        analysisDisplayMode = AnalysisDisplayMode.REVIEW_GRAPH,
        indexedGraphRequestStates = indexedGraphRequestStates + (IndexedGraphView.REVIEW to requestState),
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = requestState.toApplicationFeedbackLevel(),
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "loadReviewGraph",
    )
}

/** 判定收到的索引图请求是否仍是当前等待的请求，避免过时响应覆盖最新状态。 */
private fun GraphEditorStateSnapshot.shouldApplyIndexedGraphRequestState(
    view: IndexedGraphView,
    requestState: AsyncRequestState,
): Boolean {
    val incomingRequestId = requestState.requestId ?: return true
    val currentRequestId = indexedGraphRequestStates[view]?.requestId ?: return true
    return incomingRequestId == currentRequestId
}

/** 把索引图视图枚举映射到对应的展示模式，便于复用统一的状态切换路径。 */
private fun IndexedGraphView.toAnalysisDisplayMode(): AnalysisDisplayMode =
    when (this) {
        IndexedGraphView.ARCHITECTURE -> AnalysisDisplayMode.ARCHITECTURE_GRAPH
        IndexedGraphView.CLASS_DIAGRAM -> AnalysisDisplayMode.CLASS_DIAGRAM
        IndexedGraphView.REVIEW -> AnalysisDisplayMode.REVIEW_GRAPH
    }

/** 把指定索引图视图还原为空结果，通常用于发起重新请求前清空旧展示。 */
private fun GraphEditorStateSnapshot.clearIndexedGraphView(view: IndexedGraphView): GraphEditorStateSnapshot =
    when (view) {
        IndexedGraphView.ARCHITECTURE -> copy(architectureGraphView = ArchitectureGraphResult())
        IndexedGraphView.CLASS_DIAGRAM -> copy(classDiagramView = ClassDiagramResult())
        IndexedGraphView.REVIEW -> copy(reviewGraphView = ReviewGraphResult())
    }

/** 把异步请求的阶段翻译成对外反馈的级别（成功/错误/信息），驱动 UI 提示样式。 */
private fun AsyncRequestState.toApplicationFeedbackLevel(): ApplicationFeedbackLevel =
    when (phase) {
        com.charmnight.linkgraph.application.model.AsyncRequestPhase.SUCCEEDED -> ApplicationFeedbackLevel.SUCCESS
        com.charmnight.linkgraph.application.model.AsyncRequestPhase.FAILED,
        com.charmnight.linkgraph.application.model.AsyncRequestPhase.TIMED_OUT,
        -> ApplicationFeedbackLevel.ERROR
        else -> ApplicationFeedbackLevel.INFO
    }

/** 重置由图派生出的临时状态（草稿、QA、Diff、生成代码等），可按需保留草稿和已有计划。 */
private fun GraphEditorStateSnapshot.resetDerivedGraphState(
    preserveDrafts: Boolean,
    preserveWorkbenchPlan: Boolean,
): GraphEditorStateSnapshot {
    return copy(
        draftWorkbenchState = if (preserveDrafts) draftWorkbenchState else DraftWorkbenchState(),
        draftPatchPreview = null,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        qaResult = null,
        qaRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        diff = null,
        diffGraph = null,
        importedMermaid = null,
        exportedMermaid = null,
        mermaidIssues = emptyList(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        lastGraphEditTransaction = null,
        lastGraphEditRejection = null,
        draftVersion = if (preserveDrafts) draftVersion else 0,
        generationPlan = if (preserveWorkbenchPlan) generationPlan else null,
        generationPlanDraftVersion = if (preserveWorkbenchPlan) generationPlanDraftVersion else null,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = if (preserveWorkbenchPlan) draftValidationState else null,
        generationPlanDiscussionSession = if (preserveWorkbenchPlan) generationPlanDiscussionSession else null,
        generationPlanDiscussionRequestState = if (preserveWorkbenchPlan) generationPlanDiscussionRequestState else AsyncRequestState(),
        generatedCodeDrafts = if (preserveWorkbenchPlan) generatedCodeDrafts else emptyList(),
        generatedCodeDraftVersion = if (preserveWorkbenchPlan) generatedCodeDraftVersion else null,
        generatedCodeDraftWarnings = if (preserveWorkbenchPlan) generatedCodeDraftWarnings else emptyList(),
        generatedCodeDraftSource = if (preserveWorkbenchPlan) generatedCodeDraftSource else null,
        generatedCodeDraftPromptPreview = if (preserveWorkbenchPlan) generatedCodeDraftPromptPreview else null,
        generatedCodeDraftWriteReport = if (preserveWorkbenchPlan) generatedCodeDraftWriteReport else null,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        sourceNavigationState = SourceNavigationState(),
    )
}

/** 用一个新场景状态覆盖现有映射，返回保留插入顺序的新映射。 */
internal fun Map<GraphSceneId, GraphSceneState>.withSceneState(
    sceneId: GraphSceneId,
    state: GraphSceneState,
): Map<GraphSceneId, GraphSceneState> {
    return LinkedHashMap(this).apply {
        put(sceneId, state)
    }
}
