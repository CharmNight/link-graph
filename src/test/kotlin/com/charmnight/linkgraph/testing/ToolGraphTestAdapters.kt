package com.charmnight.linkgraph.testing

import com.charmnight.linkgraph.llm.tools.ToolGraphProjectionIndex
import com.charmnight.linkgraph.llm.tools.ToolGraphProjectionNodeMapping
import com.charmnight.linkgraph.llm.tools.ToolGraphSceneId
import com.charmnight.linkgraph.llm.tools.ToolGraphSceneState
import com.charmnight.linkgraph.llm.tools.ToolGraphSnapshot
import com.charmnight.linkgraph.llm.tools.ToolGraphView
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.view.GraphProjectionIndex

fun GraphEditorStateSnapshot.toToolGraphSnapshot(): ToolGraphSnapshot {
    return ToolGraphSnapshot(
        workspaceGraph = workspaceGraph,
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
        diffGraph = diffGraph,
        diff = diff,
        currentSceneId = currentSceneId.toToolGraphSceneId(),
        sceneStates = sceneStates.mapKeys { (sceneId, _) -> sceneId.toToolGraphSceneId() }
            .mapValues { (_, state) -> ToolGraphSceneState(selectedNodeId = state.selectedNodeId) },
        selectedMethodSignature = selectedMethodSignature,
        trustedNavigationNodes = trustedNavigationNodes,
        draftWorkbenchState = draftWorkbenchState,
        qaResult = qaResult,
    )
}

private fun GraphSceneId.toToolGraphSceneId(): ToolGraphSceneId = when (this) {
    GraphSceneId.WORKSPACE_FACT -> ToolGraphSceneId.WORKSPACE_FACT
    GraphSceneId.WORKSPACE_FLOWCHART -> ToolGraphSceneId.WORKSPACE_FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> ToolGraphSceneId.WORKSPACE_RESOURCE_RELATION
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
