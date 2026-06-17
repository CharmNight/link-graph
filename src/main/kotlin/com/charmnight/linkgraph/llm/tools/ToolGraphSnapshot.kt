package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

enum class ToolGraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    WORKSPACE_ARCHITECTURE_GRAPH,
    WORKSPACE_CLASS_DIAGRAM,
    WORKSPACE_REVIEW_GRAPH,
    DIFF,
}

data class ToolGraphSceneState(
    val selectedNodeId: String? = null,
)

data class ToolGraphView(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val projectionIndex: ToolGraphProjectionIndex = ToolGraphProjectionIndex.EMPTY,
)

data class ToolGraphSnapshot(
    val workspaceGraph: GraphDocument = GraphDocument(),
    val workspaceRevision: Long = 0,
    val semanticFactGraph: GraphDocument = GraphDocument(),
    val factGraphView: ToolGraphView = ToolGraphView(),
    val flowchartView: ToolGraphView = ToolGraphView(),
    val resourceRelationView: ToolGraphView = ToolGraphView(),
    val architectureGraphView: ToolGraphView = ToolGraphView(),
    val classDiagramView: ToolGraphView = ToolGraphView(),
    val reviewGraphView: ToolGraphView = ToolGraphView(),
    val diffGraph: GraphDocument? = null,
    val diff: GraphDiff? = null,
    val currentSceneId: ToolGraphSceneId = ToolGraphSceneId.WORKSPACE_FACT,
    val sceneStates: Map<ToolGraphSceneId, ToolGraphSceneState> = ToolGraphSceneId.entries.associateWith {
        ToolGraphSceneState()
    },
    val selectedMethodSignature: String? = null,
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    val qaResult: GraphPatchResult? = null,
) {
    fun currentSceneState(): ToolGraphSceneState = sceneStates[currentSceneId] ?: ToolGraphSceneState()
}

fun currentWorkingGraph(snapshot: ToolGraphSnapshot): GraphDocument = snapshot.workspaceGraph

fun currentWorkingGraphSource(snapshot: ToolGraphSnapshot): String {
    return if (snapshot.workspaceGraph.hasGraphContent()) "workspaceGraph" else "emptyGraph"
}

private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null
