package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch

internal fun GraphEditorStateSnapshot.withImportedMermaid(
    mermaid: String,
    graph: GraphDocument?,
    mermaidIssues: List<MermaidIssue>,
): GraphEditorStateSnapshot {
    val nextDesignBaselineGraph = graph ?: designBaselineGraph
    return copy(
        designBaselineGraph = nextDesignBaselineGraph,
        importedMermaid = mermaid,
        mermaidIssues = mermaidIssues,
        diff = null,
        diffGraph = null,
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "importMermaid",
    )
}

internal fun GraphEditorStateSnapshot.withShownDiffMode(
    graph: GraphDocument,
    diff: GraphDiff,
): GraphEditorStateSnapshot {
    val currentWorkspaceScene = currentSceneId.toAnalysisDisplayMode()?.toWorkspaceSceneId() ?: previousWorkspaceSceneId
    val diffSceneState = sceneState(GraphSceneId.DIFF).copy(
        selectedNodeId = resolveSelectedNodeId(
            graph = graph,
            selectedNodeId = sceneState(GraphSceneId.DIFF).selectedNodeId,
            selectedMethodSignature = selectedMethodSignature,
        ),
        anchorNodeId = sceneState(GraphSceneId.DIFF).anchorNodeId
            ?.takeIf { anchorNodeId -> graph.nodes.any { it.id == anchorNodeId } }
            ?: graph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(graph),
        layoutRevision = sceneState(GraphSceneId.DIFF).layoutRevision + 1,
    )
    return copy(
        diff = diff,
        diffGraph = graph,
        currentSceneId = GraphSceneId.DIFF,
        previousWorkspaceSceneId = currentWorkspaceScene,
        sceneStates = sceneStates.withSceneState(GraphSceneId.DIFF, diffSceneState),
        draftPatchPreview = graph.patch,
        lastMessageType = "showDiffMode",
        snapshotRevision = snapshotRevision + 1,
    )
}

internal fun GraphEditorStateSnapshot.withSelectedMethod(
    signature: String,
): GraphEditorStateSnapshot {
    val graph = currentVisibleGraphForMutation()
    val sceneState = currentSceneState()
    val nextSelectedNodeId = findNodeIdBySignature(graph, signature) ?: sceneState.selectedNodeId
    return copy(
        selectedMethodSignature = signature,
        sceneStates = sceneStates.withSceneState(
            currentSceneId,
            sceneState.copy(selectedNodeId = nextSelectedNodeId),
        ),
        lastMessageType = "selectedMethod",
        snapshotRevision = snapshotRevision + 1,
    ).withAssistantContextFromCurrentState()
}

internal fun GraphEditorStateSnapshot.withSelectedNode(
    nodeId: String,
): GraphEditorStateSnapshot {
    val sceneState = currentSceneState()
    return copy(
        sceneStates = sceneStates.withSceneState(
            currentSceneId,
            sceneState.copy(selectedNodeId = nodeId),
        ),
        lastMessageType = "nodeSelected",
        snapshotRevision = snapshotRevision + 1,
    ).withAssistantContextFromCurrentState()
}

internal fun GraphEditorStateSnapshot.withLayoutChanged(
    positions: Map<String, GraphLayoutPosition>,
): GraphEditorStateSnapshot {
    val sceneState = currentSceneState()
    return copy(
        sceneStates = sceneStates.withSceneState(
            currentSceneId,
            sceneState.copy(
                layoutState = sceneState.layoutState.copy(
                    positions = sceneState.layoutState.positions + positions,
                ),
                layoutRevision = sceneState.layoutRevision + 1,
            ),
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "layoutChanged",
    )
}

internal fun GraphEditorStateSnapshot.withRequestedSourceNavigation(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.RUNNING,
        ),
        lastMessageType = "requestSourceNavigation",
        snapshotRevision = snapshotRevision + 1,
    )
}

internal fun GraphEditorStateSnapshot.withOpenedSourceNavigation(
    nodeId: String,
    targetPath: String,
    line: Int?,
    column: Int?,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.SUCCEEDED,
            result = SourceNavigationResult.OPENED,
            targetPath = targetPath,
            line = line,
            column = column,
        ),
        lastMessageType = "sourceNavigationSucceeded",
        snapshotRevision = snapshotRevision + 1,
    )
}

internal fun GraphEditorStateSnapshot.withMissingSourceNavigation(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.NOT_FOUND,
        ),
        lastMessageType = "sourceNavigationNotFound",
        snapshotRevision = snapshotRevision + 1,
    )
}

internal fun GraphEditorStateSnapshot.withFailedSourceNavigation(
    nodeId: String,
    errorMessage: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.FAILED,
            errorMessage = errorMessage,
        ),
        lastMessageType = "sourceNavigationFailed",
        snapshotRevision = snapshotRevision + 1,
    )
}

internal fun GraphEditorStateSnapshot.withDraftPatchPreview(
    patch: GraphPatch,
): GraphEditorStateSnapshot {
    return copy(
        draftPatchPreview = patch,
        lastMessageType = "draftPatchPreview",
        snapshotRevision = snapshotRevision + 1,
    )
}

private fun GraphEditorStateSnapshot.currentVisibleGraphForMutation(): GraphDocument {
    return when (currentSceneId) {
        GraphSceneId.WORKSPACE_FACT -> factGraphView.visibleGraph
        GraphSceneId.WORKSPACE_FLOWCHART -> flowchartView.visibleGraph
        GraphSceneId.WORKSPACE_RESOURCE_RELATION -> resourceRelationView.visibleGraph
        GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> classDiagramView.visibleGraph
        GraphSceneId.WORKSPACE_REVIEW_GRAPH -> reviewGraphView.visibleGraph
        GraphSceneId.DIFF -> diffGraph ?: GraphDocument()
    }
}
