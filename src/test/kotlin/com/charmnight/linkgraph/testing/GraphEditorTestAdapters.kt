package com.charmnight.linkgraph.testing

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.ui.AsyncRequestState
import com.charmnight.linkgraph.ui.DraftPatchApplyResult
import com.charmnight.linkgraph.ui.DraftPatchUndoState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot
import com.charmnight.linkgraph.ui.GraphLayoutState
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.GraphSceneState
import com.charmnight.linkgraph.ui.OperationFeedback
import com.charmnight.linkgraph.ui.SourceNavigationState
import com.charmnight.linkgraph.ui.buildViewDocuments
import com.charmnight.linkgraph.ui.defaultGraphSceneStates
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

private fun GraphDocument.isNotEmptyGraph(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

fun testSnapshot(
    semanticFactGraph: GraphDocument = GraphDocument(),
    workspaceBaseGraph: GraphDocument = GraphDocument(),
    workspaceGraph: GraphDocument = GraphDocument(),
    designBaselineGraph: GraphDocument? = null,
    trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    currentSceneId: GraphSceneId? = null,
    previousWorkspaceSceneId: GraphSceneId? = null,
    sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
    draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    draftPatchPreview: GraphPatch? = null,
    draftPatchUndoState: DraftPatchUndoState? = null,
    lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
    auditResult: GraphPatchResult? = null,
    auditRequestState: AsyncRequestState = AsyncRequestState(),
    qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    runtimeArtifactSummaries: Map<String, List<com.charmnight.linkgraph.ui.RuntimeArtifactSummary>> = emptyMap(),
    diffReviewResult: GraphPatchResult? = null,
    diffReviewRequestState: AsyncRequestState = AsyncRequestState(),
    graphBeautificationResult: GraphBeautificationResult? = null,
    graphBeautificationRequestState: AsyncRequestState = AsyncRequestState(),
    diff: GraphDiff? = null,
    diffGraph: GraphDocument? = null,
    lastGraphSource: String? = null,
    frontendEntryUrl: String? = null,
    selectedMethodSignature: String? = null,
    importedMermaid: String? = null,
    exportedMermaid: String? = null,
    mermaidIssues: List<MermaidIssue> = emptyList(),
    syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    draftVersion: Long = 0,
    generationPlan: GenerationPlan? = null,
    generationPlanDraftVersion: Long? = null,
    generationPlanRequestState: AsyncRequestState = AsyncRequestState(),
    draftValidationState: DraftValidationState? = null,
    generationPlanDiscussionSession: GenerationPlanDiscussionSession? = null,
    generationPlanDiscussionRequestState: AsyncRequestState = AsyncRequestState(),
    generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    generatedCodeDraftVersion: Long? = null,
    generatedCodeDraftWarnings: List<String> = emptyList(),
    generatedCodeDraftSource: LlmResultSource? = null,
    generatedCodeDraftPromptPreview: String? = null,
    generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
    codeDraftRequestState: AsyncRequestState = AsyncRequestState(),
    codeEligibilityDecision: StageEligibilityDecision? = null,
    sourceNavigationState: SourceNavigationState = SourceNavigationState(),
    syncPreviewRequested: Boolean = false,
    toolWindowOpenRequested: Boolean = false,
    workingGraphDirty: Boolean = false,
    semanticRevision: Long = 0,
    workspaceRevision: Long = 0,
    snapshotRevision: Long = 0,
    operationFeedback: OperationFeedback? = null,
    workbenchSectionPreferences: Map<String, Boolean> = emptyMap(),
    lastMessageType: String? = null,
    visibleGraph: GraphDocument? = null,
    workingGraph: GraphDocument? = null,
    referenceWorkingGraph: GraphDocument? = null,
    referenceFactGraph: GraphDocument? = null,
    selectedNodeId: String? = null,
    layoutState: GraphLayoutState? = null,
    layoutRevision: Long? = null,
): GraphEditorStateSnapshot {
    val resolvedCurrentSceneId = currentSceneId ?: analysisDisplayMode.toSceneId()
    val resolvedWorkspaceGraph = when {
        workspaceGraph.isNotEmptyGraph() -> workspaceGraph
        workingGraph?.isNotEmptyGraph() == true -> workingGraph
        else -> visibleGraph ?: GraphDocument()
    }
    val resolvedSemanticFactGraph = when {
        semanticFactGraph.isNotEmptyGraph() -> semanticFactGraph
        referenceFactGraph?.isNotEmptyGraph() == true -> referenceFactGraph
        referenceWorkingGraph?.isNotEmptyGraph() == true -> referenceWorkingGraph
        workingGraph?.isNotEmptyGraph() == true -> workingGraph
        else -> visibleGraph ?: GraphDocument()
    }
    val resolvedWorkspaceBaseGraph = when {
        workspaceBaseGraph.isNotEmptyGraph() -> workspaceBaseGraph
        referenceWorkingGraph?.isNotEmptyGraph() == true -> referenceWorkingGraph
        resolvedSemanticFactGraph.isNotEmptyGraph() -> resolvedSemanticFactGraph
        else -> resolvedWorkspaceGraph
    }
    val baseViews = buildViewDocuments(
        workspaceGraph = resolvedWorkspaceGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val resolvedFactGraphView = if (factGraphView != FactGraphViewDocument()) {
        factGraphView
    } else {
        baseViews.factGraphView.copy(
            visibleGraph = if (analysisDisplayMode == AnalysisDisplayMode.FACT_GRAPH && visibleGraph != null) {
                visibleGraph
            } else {
                baseViews.factGraphView.visibleGraph
            },
            fullGraph = referenceFactGraph ?: baseViews.factGraphView.fullGraph,
            anchorNodeId = selectedNodeId ?: baseViews.factGraphView.anchorNodeId,
        )
    }
    val resolvedFlowchartView = if (flowchartView != FlowchartViewDocument()) {
        flowchartView
    } else {
        baseViews.flowchartView.copy(
            visibleGraph = if (analysisDisplayMode == AnalysisDisplayMode.FLOWCHART && visibleGraph != null) {
                visibleGraph
            } else {
                baseViews.flowchartView.visibleGraph
            },
            fullGraph = workingGraph ?: baseViews.flowchartView.fullGraph,
            anchorNodeId = selectedNodeId ?: baseViews.flowchartView.anchorNodeId,
        )
    }
    val resolvedResourceRelationView = if (resourceRelationView != ResourceRelationViewDocument()) {
        resourceRelationView
    } else {
        baseViews.resourceRelationView.copy(
            visibleGraph = if (analysisDisplayMode == AnalysisDisplayMode.RESOURCE_RELATION_VIEW && visibleGraph != null) {
                visibleGraph
            } else {
                baseViews.resourceRelationView.visibleGraph
            },
            fullGraph = workingGraph ?: baseViews.resourceRelationView.fullGraph,
            anchorNodeId = selectedNodeId ?: baseViews.resourceRelationView.anchorNodeId,
        )
    }
    val nextSceneStates = LinkedHashMap(defaultGraphSceneStates()).apply {
        putAll(sceneStates)
        val currentState = get(resolvedCurrentSceneId) ?: GraphSceneState()
        put(
            resolvedCurrentSceneId,
            currentState.copy(
                selectedNodeId = selectedNodeId ?: currentState.selectedNodeId,
                anchorNodeId = selectedNodeId ?: currentState.anchorNodeId,
                layoutState = layoutState ?: currentState.layoutState,
                layoutRevision = layoutRevision ?: currentState.layoutRevision,
            ),
        )
    }
    return GraphEditorStateSnapshot(
        semanticFactGraph = resolvedSemanticFactGraph,
        workspaceBaseGraph = resolvedWorkspaceBaseGraph,
        workspaceGraph = resolvedWorkspaceGraph,
        designBaselineGraph = designBaselineGraph,
        trustedNavigationNodes = trustedNavigationNodes,
        factGraphView = resolvedFactGraphView,
        flowchartView = resolvedFlowchartView,
        resourceRelationView = resolvedResourceRelationView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = resolvedCurrentSceneId,
        previousWorkspaceSceneId = previousWorkspaceSceneId ?: resolvedCurrentSceneId.takeUnless { it == GraphSceneId.DIFF }
            ?: analysisDisplayMode.toSceneId(),
        sceneStates = nextSceneStates,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = draftPatchPreview,
        draftPatchUndoState = draftPatchUndoState,
        lastDraftPatchApplyResult = lastDraftPatchApplyResult,
        auditResult = auditResult,
        auditRequestState = auditRequestState,
        qaRequestRecoveryState = qaRequestRecoveryState,
        runtimeArtifactSummaries = runtimeArtifactSummaries,
        diffReviewResult = diffReviewResult,
        diffReviewRequestState = diffReviewRequestState,
        graphBeautificationResult = graphBeautificationResult,
        graphBeautificationRequestState = graphBeautificationRequestState,
        diff = diff,
        diffGraph = diffGraph,
        lastGraphSource = lastGraphSource,
        frontendEntryUrl = frontendEntryUrl,
        selectedMethodSignature = selectedMethodSignature,
        importedMermaid = importedMermaid,
        exportedMermaid = exportedMermaid,
        mermaidIssues = mermaidIssues,
        syncPreviewItems = syncPreviewItems,
        draftVersion = draftVersion,
        generationPlan = generationPlan,
        generationPlanDraftVersion = generationPlanDraftVersion,
        generationPlanRequestState = generationPlanRequestState,
        draftValidationState = draftValidationState,
        generationPlanDiscussionSession = generationPlanDiscussionSession,
        generationPlanDiscussionRequestState = generationPlanDiscussionRequestState,
        generatedCodeDrafts = generatedCodeDrafts,
        generatedCodeDraftVersion = generatedCodeDraftVersion,
        generatedCodeDraftWarnings = generatedCodeDraftWarnings,
        generatedCodeDraftSource = generatedCodeDraftSource,
        generatedCodeDraftPromptPreview = generatedCodeDraftPromptPreview,
        generatedCodeDraftWriteReport = generatedCodeDraftWriteReport,
        codeDraftRequestState = codeDraftRequestState,
        codeEligibilityDecision = codeEligibilityDecision,
        sourceNavigationState = sourceNavigationState,
        syncPreviewRequested = syncPreviewRequested,
        toolWindowOpenRequested = toolWindowOpenRequested,
        workingGraphDirty = workingGraphDirty,
        semanticRevision = semanticRevision,
        workspaceRevision = workspaceRevision,
        snapshotRevision = snapshotRevision,
        operationFeedback = operationFeedback,
        workbenchSectionPreferences = workbenchSectionPreferences,
        lastMessageType = lastMessageType,
    )
}

val GraphEditorStateSnapshot.visibleGraph: GraphDocument
    get() = com.charmnight.linkgraph.ui.currentVisibleGraph(this)

val GraphEditorStateSnapshot.workingGraph: GraphDocument
    get() = workspaceGraph

val GraphEditorStateSnapshot.referenceWorkingGraph: GraphDocument
    get() = workspaceBaseGraph

val GraphEditorStateSnapshot.referenceFactGraph: GraphDocument
    get() = semanticFactGraph

val GraphEditorStateSnapshot.selectedNodeId: String?
    get() = currentSceneState().selectedNodeId

val GraphEditorStateSnapshot.layoutState: GraphLayoutState
    get() = currentSceneState().layoutState

val GraphEditorStateSnapshot.layoutRevision: Long
    get() = currentSceneState().layoutRevision

val GraphEditorStateSnapshot.diffMode: Boolean
    get() = currentSceneId == GraphSceneId.DIFF

fun GraphEditorStateService.markWorkingGraphChanged(
    graph: GraphDocument,
    selectedMethodSignature: String? = null,
    workingGraphDirty: Boolean = true,
    preserveDraftPatchUndo: Boolean = false,
) {
    markGraphChanged(
        graph = graph,
        selectedMethodSignature = selectedMethodSignature,
        preserveDraftPatchUndo = preserveDraftPatchUndo,
        workingGraphDirty = workingGraphDirty,
    )
}

fun GraphEditorStateService.markViewGraphChanged(
    graph: GraphDocument,
    displayMode: AnalysisDisplayMode = snapshot().analysisDisplayMode,
    selectedMethodSignature: String? = null,
    workingGraphDirty: Boolean = true,
    preserveDraftPatchUndo: Boolean = false,
) {
    if (snapshot().analysisDisplayMode != displayMode) {
        switchAnalysisDisplayMode(displayMode)
    }
    markGraphChanged(
        graph = graph,
        selectedMethodSignature = selectedMethodSignature,
        preserveDraftPatchUndo = preserveDraftPatchUndo,
        workingGraphDirty = workingGraphDirty,
    )
}

private fun AnalysisDisplayMode.toSceneId(): GraphSceneId = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> GraphSceneId.WORKSPACE_FACT
    AnalysisDisplayMode.FLOWCHART -> GraphSceneId.WORKSPACE_FLOWCHART
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphSceneId.WORKSPACE_RESOURCE_RELATION
}
