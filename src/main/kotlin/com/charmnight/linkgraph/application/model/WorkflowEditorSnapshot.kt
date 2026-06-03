package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState

data class WorkflowEditorSnapshot(
    val semanticFactGraph: GraphDocument = GraphDocument(),
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    val workspaceGraph: GraphDocument = GraphDocument(),
    val designBaselineGraph: GraphDocument? = null,
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    val factGraphView: ApplicationGraphView = ApplicationGraphView(),
    val flowchartView: ApplicationGraphView = ApplicationGraphView(),
    val resourceRelationView: ApplicationGraphView = ApplicationGraphView(),
    val architectureGraphView: ApplicationGraphView = ApplicationGraphView(),
    val classDiagramView: ApplicationGraphView = ApplicationGraphView(),
    val reviewGraphView: ApplicationGraphView = ApplicationGraphView(),
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val selectedNodeId: String? = null,
    val selectedMethodSignature: String? = null,
    val workingGraphDirty: Boolean = false,
    val lastGraphSource: String? = null,
    val workspaceRevision: Long = 0,
    val snapshotRevision: Long = 0,
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    val diff: GraphDiff? = null,
    val diffGraph: GraphDocument? = null,
    val qaResult: GraphPatchResult? = null,
    val diffReviewResult: GraphPatchResult? = null,
    val qaRequestState: AsyncRequestState = AsyncRequestState(),
    val qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val draftPatchPreview: GraphPatch? = null,
    val draftPatchUndo: DraftPatchUndo? = null,
    val generationPlan: GenerationPlan? = null,
    val generationPlanDiscussionSession: GenerationPlanDiscussionSession? = null,
    val generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
) {
    fun toApplicationSnapshot(): ApplicationSnapshot {
        return ApplicationSnapshot(
            workspaceBaseGraph = workspaceBaseGraph,
            workspaceGraph = workspaceGraph,
            selectedMethodSignature = selectedMethodSignature,
            draftWorkbenchState = draftWorkbenchState,
            draftPatchPreview = draftPatchPreview,
            draftPatchUndo = draftPatchUndo,
            qaResult = qaResult,
            diffReviewResult = diffReviewResult,
        )
    }
}
