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
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode as toApplicationAnalysisDisplayMode
import com.charmnight.linkgraph.application.model.toWorkspaceSceneId as toApplicationWorkspaceSceneId
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.charmnight.linkgraph.workbench.AssistantSessionState
import com.charmnight.linkgraph.workbench.AssistantResultStore

data class DraftPatchUndoState(
    val graphBeforeApply: GraphDocument,
    val patchPreview: GraphPatch? = null,
)

typealias GraphSceneId = com.charmnight.linkgraph.application.model.GraphSceneId

data class GraphSceneState(
    val selectedNodeId: String? = null,
    val anchorNodeId: String? = null,
    val layoutState: GraphLayoutState = GraphLayoutState(),
    val layoutRevision: Long = 0,
    val collapsedNodeIds: Set<String> = emptySet(),
)

internal fun defaultGraphSceneStates(): Map<GraphSceneId, GraphSceneState> = GraphSceneId.entries.associateWith { GraphSceneState() }

internal fun defaultIndexedGraphRequestStates(): Map<IndexedGraphView, AsyncRequestState> =
    IndexedGraphView.entries.associateWith { AsyncRequestState() }

fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = toApplicationWorkspaceSceneId()

fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = toApplicationAnalysisDisplayMode()

data class GraphEditorStateSnapshot(
    val semanticFactGraph: GraphDocument = GraphDocument(),
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    val workspaceGraph: GraphDocument = GraphDocument(),
    val designBaselineGraph: GraphDocument? = null,
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
    val reviewGraphView: ReviewGraphResult = ReviewGraphResult(),
    val indexedGraphRequestStates: Map<IndexedGraphView, AsyncRequestState> = defaultIndexedGraphRequestStates(),
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val previousWorkspaceSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val draftPatchPreview: GraphPatch? = null,
    val draftPatchUndoState: DraftPatchUndoState? = null,
    val lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
    val qaResult: GraphPatchResult? = null,
    val qaRequestState: AsyncRequestState = AsyncRequestState(),
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
    val lastGraphEditTransaction: GraphEditTransaction? = null,
    val lastGraphEditRejection: GraphEditRejected? = null,
    val assistantSessionState: AssistantSessionState = AssistantSessionState(sessionId = "assistant-session"),
    val assistantResultStore: AssistantResultStore = AssistantResultStore(),
    val lastMessageType: String? = null,
) {
    fun sceneState(sceneId: GraphSceneId): GraphSceneState = sceneStates[sceneId] ?: GraphSceneState()

    fun currentSceneState(): GraphSceneState = sceneState(currentSceneId)
}

data class WorkspaceState(
    val semanticFactGraph: GraphDocument = GraphDocument(),
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    val workspaceGraph: GraphDocument = GraphDocument(),
    val designBaselineGraph: GraphDocument? = null,
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    val importedMermaid: String? = null,
    val exportedMermaid: String? = null,
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    val diff: GraphDiff? = null,
    val diffGraph: GraphDocument? = null,
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    val selectedMethodSignature: String? = null,
    val workingGraphDirty: Boolean = false,
    val semanticRevision: Long = 0,
    val workspaceRevision: Long = 0,
    val lastGraphEditTransaction: GraphEditTransaction? = null,
    val lastGraphEditRejection: GraphEditRejected? = null,
)

data class GraphViewsState(
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
    val reviewGraphView: ReviewGraphResult = ReviewGraphResult(),
    val indexedGraphRequestStates: Map<IndexedGraphView, AsyncRequestState> = defaultIndexedGraphRequestStates(),
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val previousWorkspaceSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
)

data class AssistantState(
    val sessionState: AssistantSessionState = AssistantSessionState(sessionId = "assistant-session"),
    val resultStore: AssistantResultStore = AssistantResultStore(),
    val runtimeArtifactSummaries: Map<String, List<RuntimeArtifactSummary>> = emptyMap(),
)

data class ReviewState(
    val qaResult: GraphPatchResult? = null,
    val qaRequestState: AsyncRequestState = AsyncRequestState(),
    val qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    val diffReviewResult: GraphPatchResult? = null,
    val diffReviewRequestState: AsyncRequestState = AsyncRequestState(),
    val graphBeautificationResult: GraphBeautificationResult? = null,
    val graphBeautificationRequestState: AsyncRequestState = AsyncRequestState(),
)

data class GenerationState(
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val draftPatchPreview: GraphPatch? = null,
    val draftPatchUndoState: DraftPatchUndoState? = null,
    val lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
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
)

data class NavigationState(
    val sourceNavigationState: SourceNavigationState = SourceNavigationState(),
    val syncPreviewRequested: Boolean = false,
    val toolWindowOpenRequested: Boolean = false,
)

data class TransportState(
    val snapshotRevision: Long = 0,
    val operationFeedback: OperationFeedback? = null,
    val lastMessageType: String? = null,
    val lastGraphSource: String? = null,
    val frontendEntryUrl: String? = null,
)

data class GraphEditorDomainStates(
    val workspace: WorkspaceState = WorkspaceState(),
    val graphViews: GraphViewsState = GraphViewsState(),
    val assistant: AssistantState = AssistantState(),
    val review: ReviewState = ReviewState(),
    val generation: GenerationState = GenerationState(),
    val navigation: NavigationState = NavigationState(),
    val transport: TransportState = TransportState(),
)

fun GraphEditorStateSnapshot.domainStates(): GraphEditorDomainStates =
    GraphEditorDomainStates(
        workspace = WorkspaceState(
            semanticFactGraph = semanticFactGraph,
            workspaceBaseGraph = workspaceBaseGraph,
            workspaceGraph = workspaceGraph,
            designBaselineGraph = designBaselineGraph,
            trustedNavigationNodes = trustedNavigationNodes,
            importedMermaid = importedMermaid,
            exportedMermaid = exportedMermaid,
            mermaidIssues = mermaidIssues,
            diff = diff,
            diffGraph = diffGraph,
            syncPreviewItems = syncPreviewItems,
            selectedMethodSignature = selectedMethodSignature,
            workingGraphDirty = workingGraphDirty,
            semanticRevision = semanticRevision,
            workspaceRevision = workspaceRevision,
            lastGraphEditTransaction = lastGraphEditTransaction,
            lastGraphEditRejection = lastGraphEditRejection,
        ),
        graphViews = GraphViewsState(
            factGraphView = factGraphView,
            flowchartView = flowchartView,
            resourceRelationView = resourceRelationView,
            architectureGraphView = architectureGraphView,
            classDiagramView = classDiagramView,
            reviewGraphView = reviewGraphView,
            indexedGraphRequestStates = indexedGraphRequestStates,
            analysisDisplayMode = analysisDisplayMode,
            currentSceneId = currentSceneId,
            previousWorkspaceSceneId = previousWorkspaceSceneId,
            sceneStates = sceneStates,
        ),
        assistant = AssistantState(
            sessionState = assistantSessionState,
            resultStore = assistantResultStore,
            runtimeArtifactSummaries = runtimeArtifactSummaries,
        ),
        review = ReviewState(
            qaResult = qaResult,
            qaRequestState = qaRequestState,
            qaRequestRecoveryState = qaRequestRecoveryState,
            diffReviewResult = diffReviewResult,
            diffReviewRequestState = diffReviewRequestState,
            graphBeautificationResult = graphBeautificationResult,
            graphBeautificationRequestState = graphBeautificationRequestState,
        ),
        generation = GenerationState(
            draftWorkbenchState = draftWorkbenchState,
            draftPatchPreview = draftPatchPreview,
            draftPatchUndoState = draftPatchUndoState,
            lastDraftPatchApplyResult = lastDraftPatchApplyResult,
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
        ),
        navigation = NavigationState(
            sourceNavigationState = sourceNavigationState,
            syncPreviewRequested = syncPreviewRequested,
            toolWindowOpenRequested = toolWindowOpenRequested,
        ),
        transport = TransportState(
            snapshotRevision = snapshotRevision,
            operationFeedback = operationFeedback,
            lastMessageType = lastMessageType,
            lastGraphSource = lastGraphSource,
            frontendEntryUrl = frontendEntryUrl,
        ),
    )

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
    val level: ApplicationFeedbackLevel,
    val message: String,
)
