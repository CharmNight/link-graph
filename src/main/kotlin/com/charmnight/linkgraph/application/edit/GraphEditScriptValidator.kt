package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

class GraphEditScriptValidator {
    fun validate(
        graph: GraphDocument,
        request: GraphEditRequest,
        resolution: GraphEditResolution = GraphEditResolution.identity(),
    ): List<GraphEditIssue> {
        if (request.operations.isEmpty()) {
            return listOf(
                GraphEditIssue(
                    code = GraphEditIssueCode.EMPTY_OPERATIONS,
                    message = "图编辑请求至少需要包含一个操作。",
                ),
            )
        }

        val issues = mutableListOf<GraphEditIssue>()
        val knownNodeIds = graph.nodes.mapTo(linkedSetOf(), GraphNode::id)
        val knownEdgeIds = graph.edges.mapTo(linkedSetOf(), GraphEdge::id)
        val upsertedNodeIds = linkedSetOf<String>()
        val upsertedEdgeIds = linkedSetOf<String>()

        request.operations.forEachIndexed { index, operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    val targetNodeId = resolution.nodeTargetId(operation.node.id)
                    if (!upsertedNodeIds.add(targetNodeId)) {
                        issues += issue(
                            GraphEditIssueCode.DUPLICATE_NODE_ID,
                            "同一图编辑请求中重复提交了节点 $targetNodeId。",
                            index,
                            operation.node.id,
                        )
                    }
                    knownNodeIds += targetNodeId
                }
                is GraphEditOperation.RemoveNode -> {
                    val nodeIds = resolution.nodeRemovalIds(operation.nodeId)
                    val existingNodeIds = nodeIds.filterTo(linkedSetOf()) { nodeId -> nodeId in knownNodeIds }
                    if (existingNodeIds.isEmpty()) {
                        issues += issue(
                            GraphEditIssueCode.MISSING_NODE,
                            "要删除的节点 ${operation.nodeId} 不存在。",
                            index,
                            operation.nodeId,
                        )
                    } else {
                        knownNodeIds -= existingNodeIds
                        knownEdgeIds -= graph.edges
                            .asSequence()
                            .filter { edge -> edge.fromNodeId in existingNodeIds || edge.toNodeId in existingNodeIds }
                            .map(GraphEdge::id)
                            .toSet()
                    }
                }
                is GraphEditOperation.UpsertEdge -> {
                    val targetEdgeId = resolution.edgeTargetId(operation.edge.id)
                    if (!upsertedEdgeIds.add(targetEdgeId)) {
                        issues += issue(
                            GraphEditIssueCode.DUPLICATE_EDGE_ID,
                            "同一图编辑请求中重复提交了边 $targetEdgeId。",
                            index,
                            operation.edge.id,
                        )
                    }
                    val fromNodeId = resolution.nodeTargetId(operation.edge.fromNodeId)
                    val toNodeId = resolution.nodeTargetId(operation.edge.toNodeId)
                    if (fromNodeId !in knownNodeIds || toNodeId !in knownNodeIds) {
                        issues += issue(
                            GraphEditIssueCode.INVALID_EDGE_ENDPOINT,
                            "边 ${operation.edge.id} 的端点不存在。",
                            index,
                            operation.edge.id,
                        )
                    }
                    knownEdgeIds += targetEdgeId
                }
                is GraphEditOperation.RemoveEdge -> {
                    val edgeIds = resolution.edgeRemovalIds(operation.edgeId)
                    val existingEdgeIds = edgeIds.filterTo(linkedSetOf()) { edgeId -> edgeId in knownEdgeIds }
                    if (existingEdgeIds.isEmpty()) {
                        issues += issue(
                            GraphEditIssueCode.MISSING_EDGE,
                            "要删除的边 ${operation.edgeId} 不存在。",
                            index,
                            operation.edgeId,
                        )
                    } else {
                        knownEdgeIds -= existingEdgeIds
                    }
                }
            }
        }

        return issues
    }

    private fun issue(
        code: GraphEditIssueCode,
        message: String,
        operationIndex: Int,
        targetId: String,
    ): GraphEditIssue =
        GraphEditIssue(
            code = code,
            message = message,
            operationIndex = operationIndex,
            targetId = targetId,
            retryable = false,
        )
}
