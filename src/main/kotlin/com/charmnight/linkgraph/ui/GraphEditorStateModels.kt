package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.PreparedCodeEdit
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationStep
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.runtime.AgentRunArtifactSummary
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
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
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.InvestigationEvidenceDelta
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationTurnOutcome
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
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

internal fun GraphEditorStateSnapshot.freeze(): GraphEditorStateSnapshot {
    return copy(
        semanticFactGraph = semanticFactGraph.freeze(),
        workspaceBaseGraph = workspaceBaseGraph.freeze(),
        workspaceGraph = workspaceGraph.freeze(),
        designBaselineGraph = designBaselineGraph?.freeze(),
        trustedNavigationNodes = trustedNavigationNodes.mapValues { (_, node) -> node.freeze() }.toMap(),
        factGraphView = factGraphView.copy(
            visibleGraph = factGraphView.visibleGraph.freeze(),
            fullGraph = factGraphView.fullGraph.freeze(),
        ),
        flowchartView = flowchartView.copy(
            visibleGraph = flowchartView.visibleGraph.freeze(),
            fullGraph = flowchartView.fullGraph.freeze(),
        ),
        resourceRelationView = resourceRelationView.copy(
            visibleGraph = resourceRelationView.visibleGraph.freeze(),
            fullGraph = resourceRelationView.fullGraph.freeze(),
        ),
        sceneStates = sceneStates.mapValues { (_, sceneState) -> sceneState.freeze() }.toMap(),
        draftWorkbenchState = draftWorkbenchState.freeze(),
        draftPatchPreview = draftPatchPreview?.freeze(),
        draftPatchUndoState = draftPatchUndoState?.let { undoState ->
            undoState.copy(
                graphBeforeApply = undoState.graphBeforeApply.freeze(),
                patchPreview = undoState.patchPreview?.freeze(),
            )
        },
        lastDraftPatchApplyResult = lastDraftPatchApplyResult?.copy(
            appliedNodeIds = lastDraftPatchApplyResult.appliedNodeIds.toList(),
            appliedEdgeIds = lastDraftPatchApplyResult.appliedEdgeIds.toList(),
            appliedTargets = lastDraftPatchApplyResult.appliedTargets.toList(),
        ),
        auditResult = auditResult?.freeze(),
        qaRequestRecoveryState = qaRequestRecoveryState.freeze(),
        runtimeArtifactSummaries = runtimeArtifactSummaries.mapValues { (_, summaries) -> summaries.toList() }.toMap(),
        diffReviewResult = diffReviewResult?.freeze(),
        graphBeautificationResult = graphBeautificationResult?.freeze(),
        diff = diff?.freeze(),
        diffGraph = diffGraph?.freeze(),
        mermaidIssues = mermaidIssues.toList(),
        syncPreviewItems = syncPreviewItems.toList(),
        generationPlan = generationPlan?.freeze(),
        draftValidationState = draftValidationState?.freeze(),
        generationPlanDiscussionSession = generationPlanDiscussionSession?.freeze(),
        generatedCodeDrafts = generatedCodeDrafts.map { draft -> draft.freeze() },
        generatedCodeDraftWarnings = generatedCodeDraftWarnings.toList(),
        generatedCodeDraftWriteReport = generatedCodeDraftWriteReport?.freeze(),
        codeEligibilityDecision = codeEligibilityDecision?.copy(
            blockingThreadIds = codeEligibilityDecision.blockingThreadIds.toList(),
            unresolvedThreadIds = codeEligibilityDecision.unresolvedThreadIds.toList(),
        ),
        workbenchSectionPreferences = workbenchSectionPreferences.toMap(),
    )
}

private fun GraphSceneState.freeze(): GraphSceneState {
    return copy(
        layoutState = layoutState.copy(positions = layoutState.positions.toMap()),
        collapsedNodeIds = collapsedNodeIds.toSet(),
    )
}

private fun GraphDocument.freeze(): GraphDocument {
    return copy(
        nodes = nodes.map { node -> node.freeze() },
        edges = edges.map { edge -> edge.freeze() },
        patch = patch?.freeze(),
    )
}

private fun GraphNode.freeze(): GraphNode {
    return copy(
        inputs = inputs.toList(),
        outputs = outputs.toList(),
        diff = diff.freeze(),
        evidence = evidence.toList(),
        metadata = metadata.toMap(),
    )
}

private fun GraphEdge.freeze(): GraphEdge {
    return copy(
        diff = diff.freeze(),
        evidence = evidence.toList(),
        metadata = metadata.toMap(),
    )
}

private fun GraphDiff.freeze(): GraphDiff {
    return copy(
        fields = fields.toList(),
        entries = entries.map { entry ->
            entry.copy(fields = entry.fields.toList())
        },
    )
}

private fun GraphPatch.freeze(): GraphPatch {
    return copy(
        operations = operations.map { operation ->
            operation.copy(
                node = operation.node?.freeze(),
                edge = operation.edge?.freeze(),
                metadata = operation.metadata.toMap(),
            )
        },
        addedNodeIds = addedNodeIds.toList(),
        removedNodeIds = removedNodeIds.toList(),
        addedEdgeIds = addedEdgeIds.toList(),
        removedEdgeIds = removedEdgeIds.toList(),
    )
}

private fun DraftWorkbenchState.freeze(): DraftWorkbenchState {
    return copy(
        draftChanges = draftChanges.map { entry -> entry.freeze() },
        draftNotes = draftNotes.map { entry -> entry.freeze() },
    )
}

private fun DraftWorkbenchEntry.freeze(): DraftWorkbenchEntry {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
        editScopes = editScopes.map { scope -> scope.freeze() },
        graphPatch = graphPatch?.freeze(),
    )
}

private fun GraphPatchResult.freeze(): GraphPatchResult {
    return copy(
        patch = patch?.freeze(),
        findings = findings.map { finding -> finding.freeze() },
        candidateChanges = candidateChanges.map { change -> change.freeze() },
        newCandidateChanges = newCandidateChanges.map { change -> change.freeze() },
        investigationThreads = investigationThreads.map { thread -> thread.freeze() },
        latestTurnOutcome = latestTurnOutcome?.freeze(),
        recentTurnOutcomes = recentTurnOutcomes.map { outcome -> outcome.freeze() },
        sourceContext = sourceContext.map { snippet -> snippet.freeze() },
        evidenceTrace = evidenceTrace.map { trace -> trace.freeze() },
        auditSession = auditSession?.freeze(),
        warnings = warnings.toList(),
    )
}

private fun ResultEvidenceFinding.freeze(): ResultEvidenceFinding {
    return copy(references = references.toList())
}

private fun CandidateDraftChange.freeze(): CandidateDraftChange {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
        editScopes = editScopes.map { scope -> scope.freeze() },
        graphPatch = graphPatch?.freeze(),
    )
}

private fun InvestigationThread.freeze(): InvestigationThread {
    return copy(
        targetStepIds = targetStepIds.toList(),
        targetNodeIds = targetNodeIds.toList(),
        evidence = evidence.map { finding -> finding.freeze() },
    )
}

private fun InvestigationTurnOutcome.freeze(): InvestigationTurnOutcome {
    return copy(
        evidenceDelta = evidenceDelta.freeze(),
        observedNodeIds = observedNodeIds.toList(),
        observedFilePaths = observedFilePaths.toList(),
    )
}

private fun InvestigationEvidenceDelta.freeze(): InvestigationEvidenceDelta {
    return copy(
        addedNodeIds = addedNodeIds.toList(),
        addedFilePaths = addedFilePaths.toList(),
    )
}

private fun AuditConversationSession.freeze(): AuditConversationSession {
    return copy(
        messages = messages.toList(),
        candidateChanges = candidateChanges.map { change -> change.freeze() },
        investigationThreads = investigationThreads.map { thread -> thread.freeze() },
        turnOutcomes = turnOutcomes.map { outcome -> outcome.freeze() },
    )
}

private fun QaRequestRecoveryState.freeze(): QaRequestRecoveryState {
    return copy(
        lastSubmittedRequest = lastSubmittedRequest?.freeze(),
        lastFailedRequest = lastFailedRequest?.freeze(),
    )
}

private fun ReplayableQaRequest.freeze(): ReplayableQaRequest {
    return copy(
        selectedNodeIds = selectedNodeIds.toList(),
        baseSession = baseSession?.freeze(),
    )
}

private fun GenerationPlan.freeze(): GenerationPlan {
    return copy(
        items = items.toList(),
        warnings = warnings.toList(),
    )
}

private fun DraftValidationState.freeze(): DraftValidationState {
    return copy(
        unresolvedThreadIds = unresolvedThreadIds.toList(),
        unresolvedThreads = unresolvedThreads.map { thread -> thread.freeze() },
    )
}

private fun GenerationPlanDiscussionSession.freeze(): GenerationPlanDiscussionSession {
    return copy(messages = messages.toList())
}

private fun GraphBeautificationResult.freeze(): GraphBeautificationResult {
    return copy(
        steps = steps.map { step -> step.freeze() },
        warnings = warnings.toList(),
    )
}

private fun GraphBeautificationStep.freeze(): GraphBeautificationStep {
    return copy(
        evidence = evidence.map { finding -> finding.freeze() },
        followUpQuestions = followUpQuestions.toList(),
        downstreamTargets = downstreamTargets.toList(),
    )
}

private fun GeneratedCodeDraftWriteReport.freeze(): GeneratedCodeDraftWriteReport {
    return copy(
        writtenFiles = writtenFiles.toList(),
        skippedFiles = skippedFiles.toList(),
        warnings = warnings.toList(),
    )
}

private fun PreparedCodeEdit.freeze(): PreparedCodeEdit {
    return copy(warnings = warnings.toList())
}

private fun GeneratedCodeDraft.freeze(): GeneratedCodeDraft {
    return copy(
        editOperations = editOperations.map { operation ->
            operation.copy(warnings = operation.warnings.toList())
        },
        editScopes = editScopes.map { scope ->
            scope.freeze()
        },
        preparedEdits = preparedEdits.map { edit -> edit.freeze() },
        warnings = warnings.toList(),
    )
}

private fun com.charmnight.linkgraph.llm.EditScope.freeze(): com.charmnight.linkgraph.llm.EditScope {
    return copy(
        allowedChangeKinds = allowedChangeKinds.toList(),
        supportingFindingIds = supportingFindingIds.toList(),
    )
}

private fun SourceSnippetContext.freeze(): SourceSnippetContext = copy()

private fun com.charmnight.linkgraph.llm.EvidenceTraceEntry.freeze(): com.charmnight.linkgraph.llm.EvidenceTraceEntry {
    return copy(mappingTrace = mappingTrace.toList())
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
