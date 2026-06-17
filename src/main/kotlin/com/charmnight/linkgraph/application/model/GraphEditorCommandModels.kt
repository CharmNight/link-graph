package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

enum class GraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    WORKSPACE_ARCHITECTURE_GRAPH,
    WORKSPACE_CLASS_DIAGRAM,
    WORKSPACE_REVIEW_GRAPH,
    DIFF,
}

fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> GraphSceneId.WORKSPACE_FACT
    AnalysisDisplayMode.FLOWCHART -> GraphSceneId.WORKSPACE_FLOWCHART
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphSceneId.WORKSPACE_RESOURCE_RELATION
    AnalysisDisplayMode.ARCHITECTURE_GRAPH -> GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH
    AnalysisDisplayMode.CLASS_DIAGRAM -> GraphSceneId.WORKSPACE_CLASS_DIAGRAM
    AnalysisDisplayMode.REVIEW_GRAPH -> GraphSceneId.WORKSPACE_REVIEW_GRAPH
}

fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = when (this) {
    GraphSceneId.WORKSPACE_FACT -> AnalysisDisplayMode.FACT_GRAPH
    GraphSceneId.WORKSPACE_FLOWCHART -> AnalysisDisplayMode.FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> AnalysisDisplayMode.RESOURCE_RELATION_VIEW
    GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> AnalysisDisplayMode.ARCHITECTURE_GRAPH
    GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> AnalysisDisplayMode.CLASS_DIAGRAM
    GraphSceneId.WORKSPACE_REVIEW_GRAPH -> AnalysisDisplayMode.REVIEW_GRAPH
    GraphSceneId.DIFF -> null
}

data class GraphLayoutPosition(
    val x: Double,
    val y: Double,
)

enum class GraphEditRequestSource {
    FRONTEND,
    AI_TOOL,
    DEBUG_AUTOMATION,
}

data class GraphEditRequest(
    val sceneId: GraphSceneId,
    val baseWorkspaceRevision: Long,
    val operations: List<GraphEditOperation>,
    val source: GraphEditRequestSource,
)

sealed interface GraphEditOperation {
    data class UpsertNode(
        val node: GraphNode,
    ) : GraphEditOperation

    data class RemoveNode(
        val nodeId: String,
    ) : GraphEditOperation

    data class UpsertEdge(
        val edge: GraphEdge,
    ) : GraphEditOperation

    data class RemoveEdge(
        val edgeId: String,
    ) : GraphEditOperation
}

enum class GraphEditIssueCode {
    STALE_BASE_REVISION,
    EMPTY_OPERATIONS,
    DUPLICATE_NODE_ID,
    DUPLICATE_EDGE_ID,
    MISSING_NODE,
    MISSING_EDGE,
    INVALID_EDGE_ENDPOINT,
    READONLY_PROJECTION_NODE,
    READONLY_PROJECTION_EDGE,
    UNSUPPORTED_SCENE,
    INVALID_NODE_TYPE,
    INVALID_EDGE_TYPE,
    INVALID_SOURCE_TAG,
    PAYLOAD_TOO_LARGE,
}

data class GraphEditIssue(
    val code: GraphEditIssueCode,
    val message: String,
    val operationIndex: Int? = null,
    val targetId: String? = null,
    val retryable: Boolean = false,
)

data class GraphEditRejected(
    val issues: List<GraphEditIssue>,
    val currentWorkspaceRevision: Long,
)

sealed interface GraphEditResult {
    data class Applied(
        val graph: com.charmnight.linkgraph.model.GraphDocument,
        val transaction: GraphEditTransaction,
    ) : GraphEditResult

    data class Rejected(
        val rejection: GraphEditRejected,
    ) : GraphEditResult
}

data class GraphEditTransaction(
    val graphBeforeApply: com.charmnight.linkgraph.model.GraphDocument,
    val graphAfterApply: com.charmnight.linkgraph.model.GraphDocument,
    val request: GraphEditRequest,
    val appliedOperations: List<GraphEditOperation>,
    val source: GraphEditRequestSource,
    val workspaceRevisionBefore: Long,
    val workspaceRevisionAfter: Long,
)
