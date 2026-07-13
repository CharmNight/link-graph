package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.agent.tools.ToolGraphSceneId
import com.charmnight.linkgraph.agent.tools.ToolGraphSceneState
import com.charmnight.linkgraph.agent.tools.ToolGraphSnapshot
import com.charmnight.linkgraph.agent.tools.ToolGraphView
import com.charmnight.linkgraph.agent.tools.ToolGraphProjectionIndex
import com.charmnight.linkgraph.agent.tools.ToolGraphProjectionNodeMapping
import com.charmnight.linkgraph.agent.model.InvocationExpansionContextMode as AgentInvocationExpansionContextMode
import com.charmnight.linkgraph.agent.model.InvocationExpansionSceneState as AgentInvocationExpansionSceneState
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.application.model.GraphProjectionIndex

internal fun GraphEditorStateSnapshot.toToolGraphSnapshot(): ToolGraphSnapshot {
    return ToolGraphSnapshot(
        workspaceGraph = workspaceGraph,
        workspaceRevision = workspaceRevision,
        semanticFactGraph = semanticFactGraph,
        factGraphView = ToolGraphView(
            visibleGraph = factGraphView.visibleGraph,
            fullGraph = factGraphView.fullGraph,
            projectionIndex = factGraphView.projectionIndex.toToolGraphProjectionIndex(),
        ),
        flowchartView = ToolGraphView(
            visibleGraph = flowchartView.visibleGraph,
            fullGraph = flowchartView.fullGraph,
            projectionIndex = flowchartView.projectionIndex.toToolGraphProjectionIndex(),
        ),
        resourceRelationView = ToolGraphView(
            visibleGraph = resourceRelationView.visibleGraph,
            fullGraph = resourceRelationView.fullGraph,
            projectionIndex = resourceRelationView.projectionIndex.toToolGraphProjectionIndex(),
        ),
        architectureGraphView = ToolGraphView(
            visibleGraph = architectureGraphView.visibleGraph,
            fullGraph = architectureGraphView.fullGraph,
            projectionIndex = architectureGraphView.projectionIndex.toToolGraphProjectionIndex(),
        ),
        classDiagramView = ToolGraphView(
            visibleGraph = classDiagramView.visibleGraph,
            fullGraph = classDiagramView.fullGraph,
            projectionIndex = classDiagramView.projectionIndex.toToolGraphProjectionIndex(),
        ),
        reviewGraphView = ToolGraphView(
            visibleGraph = reviewGraphView.visibleGraph,
            fullGraph = reviewGraphView.fullGraph,
            projectionIndex = reviewGraphView.projectionIndex.toToolGraphProjectionIndex(),
        ),
        diffGraph = diffGraph,
        diff = diff,
        currentSceneId = currentSceneId.toToolGraphSceneId(),
        sceneStates = sceneStates.mapKeys { (sceneId, _) -> sceneId.toToolGraphSceneId() }
            .mapValues { (_, state) ->
                ToolGraphSceneState(
                    selectedNodeId = state.selectedNodeId,
                    invocationExpansionState = state.invocationExpansionState.toAgentInvocationExpansionSceneState(),
                )
            },
        selectedMethodSignature = selectedMethodSignature,
        trustedNavigationNodes = trustedNavigationNodes,
        draftWorkbenchState = draftWorkbenchState,
        // P4-2：从 qaResult 派生中性候选变更列表（避免 ToolGraphSnapshot 反向依赖 llm.GraphPatchResult）
        pendingCandidateChanges = qaResult?.candidateChanges.orEmpty(),
    )
}

private fun GraphSceneId.toToolGraphSceneId(): ToolGraphSceneId = when (this) {
    GraphSceneId.WORKSPACE_FACT -> ToolGraphSceneId.WORKSPACE_FACT
    GraphSceneId.WORKSPACE_FLOWCHART -> ToolGraphSceneId.WORKSPACE_FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> ToolGraphSceneId.WORKSPACE_RESOURCE_RELATION
    GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> ToolGraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH
    GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> ToolGraphSceneId.WORKSPACE_CLASS_DIAGRAM
    GraphSceneId.WORKSPACE_REVIEW_GRAPH -> ToolGraphSceneId.WORKSPACE_REVIEW_GRAPH
    GraphSceneId.DIFF -> ToolGraphSceneId.DIFF
}

private fun GraphProjectionIndex.toToolGraphProjectionIndex(): ToolGraphProjectionIndex {
    return ToolGraphProjectionIndex(
        nodeMappings = nodeMappings.mapValues { (_, mapping) ->
            ToolGraphProjectionNodeMapping(
                projectedNodeId = mapping.projectedNodeId,
                canonicalNodeIds = mapping.canonicalNodeIds,
            )
        },
    )
}

private fun InvocationExpansionSceneState.toAgentInvocationExpansionSceneState(): AgentInvocationExpansionSceneState =
    AgentInvocationExpansionSceneState(
        activeExpansionId = activeExpansionId,
        activeExpansionPath = activeExpansionPath,
        collapsedExpansionIds = collapsedExpansionIds,
        activeSiblingByParentContext = activeSiblingByParentContext,
        contextMode = when (contextMode) {
            InvocationExpansionContextMode.ACTIVE_CHAIN -> AgentInvocationExpansionContextMode.ACTIVE_CHAIN
        },
    )
