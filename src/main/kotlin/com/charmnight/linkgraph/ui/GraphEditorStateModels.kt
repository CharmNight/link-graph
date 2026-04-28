package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunArtifactSummary
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

data class DraftPatchUndoState(
    val graphBeforeApply: GraphDocument,
    val patchPreview: GraphPatch? = null,
)

enum class GraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    DIFF,
}

data class GraphSceneState(
    val selectedNodeId: String? = null,
    val anchorNodeId: String? = null,
    val layoutState: GraphLayoutState = GraphLayoutState(),
    val layoutRevision: Long = 0,
    val collapsedNodeIds: Set<String> = emptySet(),
)

internal fun defaultGraphSceneStates(): Map<GraphSceneId, GraphSceneState> = GraphSceneId.entries.associateWith { GraphSceneState() }

fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> GraphSceneId.WORKSPACE_FACT
    AnalysisDisplayMode.FLOWCHART -> GraphSceneId.WORKSPACE_FLOWCHART
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphSceneId.WORKSPACE_RESOURCE_RELATION
}

fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = when (this) {
    GraphSceneId.WORKSPACE_FACT -> AnalysisDisplayMode.FACT_GRAPH
    GraphSceneId.WORKSPACE_FLOWCHART -> AnalysisDisplayMode.FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> AnalysisDisplayMode.RESOURCE_RELATION_VIEW
    GraphSceneId.DIFF -> null
}

data class GraphEditorStateSnapshot(
    val semanticFactGraph: GraphDocument = GraphDocument(),
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    val workspaceGraph: GraphDocument = GraphDocument(),
    val designBaselineGraph: GraphDocument? = null,
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val previousWorkspaceSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val draftPatchPreview: GraphPatch? = null,
    val draftPatchUndoState: DraftPatchUndoState? = null,
    val lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
    val auditResult: GraphPatchResult? = null,
    val auditRequestState: AsyncRequestState = AsyncRequestState(),
    val qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    val runtimeArtifactSummaries: Map<String, List<RuntimeArtifactSummary>> = emptyMap(),
    val diffReviewResult: GraphPatchResult? = null,
    val diffReviewRequestState: AsyncRequestState = AsyncRequestState(),
    val graphBeautificationResult: GraphBeautificationResult? = null,
    val graphBeautificationRequestState: AsyncRequestState = AsyncRequestState(),
    val diff: GraphDiff? = null,
    val diffGraph: GraphDocument? = null,
    val lastGraphSource: String? = null,
    val frontendEntryUrl: String? = null,
    val selectedMethodSignature: String? = null,
    val importedMermaid: String? = null,
    val exportedMermaid: String? = null,
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    val draftVersion: Long = 0,
    val generationPlan: GenerationPlan? = null,
    val generationPlanDraftVersion: Long? = null,
    val generationPlanRequestState: AsyncRequestState = AsyncRequestState(),
    val draftValidationState: DraftValidationState? = null,
    val generationPlanDiscussionSession: GenerationPlanDiscussionSession? = null,
    val generationPlanDiscussionRequestState: AsyncRequestState = AsyncRequestState(),
    val generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    val generatedCodeDraftVersion: Long? = null,
    val generatedCodeDraftWarnings: List<String> = emptyList(),
    val generatedCodeDraftSource: LlmResultSource? = null,
    val generatedCodeDraftPromptPreview: String? = null,
    val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
    val codeDraftRequestState: AsyncRequestState = AsyncRequestState(),
    val codeEligibilityDecision: StageEligibilityDecision? = null,
    val sourceNavigationState: SourceNavigationState = SourceNavigationState(),
    val syncPreviewRequested: Boolean = false,
    val toolWindowOpenRequested: Boolean = false,
    val workingGraphDirty: Boolean = false,
    val semanticRevision: Long = 0,
    val workspaceRevision: Long = 0,
    val snapshotRevision: Long = 0,
    val operationFeedback: OperationFeedback? = null,
    val workbenchSectionPreferences: Map<String, Boolean> = emptyMap(),
    val lastMessageType: String? = null,
) {
    fun sceneState(sceneId: GraphSceneId): GraphSceneState = sceneStates[sceneId] ?: GraphSceneState()

    fun currentSceneState(): GraphSceneState = sceneState(currentSceneId)
}

data class RuntimeArtifactSummary(
    val artifactId: String,
    val artifactType: String,
    val title: String,
    val description: String? = null,
) {
    companion object {
        fun from(summary: AgentRunArtifactSummary): RuntimeArtifactSummary {
            return RuntimeArtifactSummary(
                artifactId = summary.artifactId,
                artifactType = summary.artifactType,
                title = summary.title,
                description = summary.description,
            )
        }
    }
}

data class SourceNavigationState(
    val nodeId: String? = null,
    val phase: SourceNavigationPhase = SourceNavigationPhase.IDLE,
    val result: SourceNavigationResult? = null,
    val targetPath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val errorMessage: String? = null,
)

enum class SourceNavigationPhase {
    IDLE,
    RUNNING,
    SUCCEEDED,
    NOT_FOUND,
    FAILED,
}

enum class SourceNavigationResult {
    OPENED,
}

data class OperationFeedback(
    val level: OperationFeedbackLevel,
    val message: String,
)

enum class OperationFeedbackLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}
