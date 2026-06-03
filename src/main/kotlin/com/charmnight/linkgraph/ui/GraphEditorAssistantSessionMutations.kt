package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.workbench.AssistantContextSnapshot
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantTurnKind
import com.charmnight.linkgraph.workbench.AssistantTurnRef

internal fun GraphEditorStateSnapshot.withAssistantContextFromCurrentState(
    activeIntent: AssistantIntent = assistantSessionState.activeIntent,
): GraphEditorStateSnapshot {
    val nextContext = if (assistantSessionState.contextLocked) {
        assistantSessionState.context
    } else {
        assistantContextSnapshot()
    }
    return copy(
        assistantSessionState = assistantSessionState.copy(
            activeIntent = activeIntent,
            context = nextContext,
        ),
    )
}

internal fun GraphEditorStateSnapshot.withAssistantSelectedDiffItemIds(
    selectedDiffItemIds: List<String>,
): GraphEditorStateSnapshot {
    if (assistantSessionState.contextLocked) {
        return this
    }
    return copy(
        assistantSessionState = assistantSessionState.copy(
            context = assistantSessionState.context.copy(
                selectedDiffItemIds = selectedDiffItemIds,
            ),
        ),
    )
}

internal fun GraphEditorStateSnapshot.withAssistantTurnRef(
    kind: AssistantTurnKind,
    activeIntent: AssistantIntent,
    sourceMessageType: String,
    resultId: String? = null,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
): GraphEditorStateSnapshot {
    val nextContext = if (assistantSessionState.contextLocked) {
        assistantSessionState.context
    } else {
        assistantContextSnapshot()
    }
    val nextTurn = AssistantTurnRef(
        turnId = buildAssistantTurnId(kind, sourceMessageType, assistantSessionState.turns.size + 1, createdAtEpochMillis),
        kind = kind,
        sourceMessageType = sourceMessageType,
        resultId = resultId,
        createdAtEpochMillis = createdAtEpochMillis,
        context = nextContext,
    )
    return copy(
        assistantSessionState = assistantSessionState.copy(
            activeIntent = activeIntent,
            context = nextContext,
            turns = assistantSessionState.turns + nextTurn,
        ),
    )
}

private fun GraphEditorStateSnapshot.assistantContextSnapshot(): AssistantContextSnapshot {
    val currentSelectedNodeId = currentSceneState().selectedNodeId?.takeIf(String::isNotBlank)
    return AssistantContextSnapshot(
        selectedNodeIds = listOfNotNull(currentSelectedNodeId),
        selectedDiffItemIds = assistantSessionState.context.selectedDiffItemIds,
        analysisDisplayMode = analysisDisplayMode.name,
        currentSceneId = currentSceneId.name,
        selectedMethodSignature = selectedMethodSignature,
        scopeLabel = resolveAssistantScopeLabel(currentSelectedNodeId),
    )
}

private fun GraphEditorStateSnapshot.resolveAssistantScopeLabel(selectedNodeId: String?): String {
    if (!selectedMethodSignature.isNullOrBlank()) {
        return selectedMethodSignature
    }
    val selectedNodeTitle = selectedNodeId
        ?.let { nodeId -> currentVisibleGraphForAssistantContext().nodes.firstOrNull { node -> node.id == nodeId } }
        ?.title
    if (!selectedNodeTitle.isNullOrBlank()) {
        return selectedNodeTitle
    }
    return analysisDisplayMode.name
}

private fun GraphEditorStateSnapshot.currentVisibleGraphForAssistantContext() =
    when (currentSceneId) {
        GraphSceneId.WORKSPACE_FACT -> factGraphView.visibleGraph
        GraphSceneId.WORKSPACE_FLOWCHART -> flowchartView.visibleGraph
        GraphSceneId.WORKSPACE_RESOURCE_RELATION -> resourceRelationView.visibleGraph
        GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> classDiagramView.visibleGraph
        GraphSceneId.WORKSPACE_REVIEW_GRAPH -> reviewGraphView.visibleGraph
        GraphSceneId.DIFF -> diffGraph ?: workspaceGraph
    }

private fun buildAssistantTurnId(
    kind: AssistantTurnKind,
    sourceMessageType: String,
    sequence: Int,
    createdAtEpochMillis: Long,
): String = "${sourceMessageType}:${kind.name.lowercase()}:$sequence:$createdAtEpochMillis"
