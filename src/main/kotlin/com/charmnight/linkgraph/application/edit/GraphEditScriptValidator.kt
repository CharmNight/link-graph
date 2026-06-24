package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

/**
 * 图编辑脚本校验器：在不真正应用编辑的前提下，逐条检查请求中的操作序列，
 * 提前发现重复节点/边、引用了不存在节点、孤立边等结构性问题，供上层在执行前给出反馈。
 */
class GraphEditScriptValidator {
    /**
     * 在给定的图基础上对一组编辑操作进行模拟校验，返回发现的所有问题。
     * 通过 [resolution] 应用节点/边的标识归一化，复用同一份"已知标识集合"模拟连续操作造成的状态变化。
     */
    fun validate(
        graph: GraphDocument,
        request: GraphEditRequest,
        resolution: GraphEditResolution = GraphEditResolution.identity(),
    ): List<GraphEditIssue> {
        // 空操作直接报错，避免上层执行无意义的写入。
        if (request.operations.isEmpty()) {
            return listOf(
                GraphEditIssue(
                    code = GraphEditIssueCode.EMPTY_OPERATIONS,
                    message = "图编辑请求至少需要包含一个操作。",
                ),
            )
        }

        val issues = mutableListOf<GraphEditIssue>()
        // 当前已知的节点标识集合，会随操作推进而被模拟更新。
        val knownNodeIds = graph.nodes.mapTo(linkedSetOf(), GraphNode::id)
        // 当前已知的边标识集合，会随操作推进而被模拟更新。
        val knownEdgeIds = graph.edges.mapTo(linkedSetOf(), GraphEdge::id)
        // 本次请求中已经处理过的 upsert 节点标识，用于检测同请求内的重复提交。
        val upsertedNodeIds = linkedSetOf<String>()
        // 本次请求中已经处理过的 upsert 边标识，用于检测同请求内的重复提交。
        val upsertedEdgeIds = linkedSetOf<String>()

        request.operations.forEachIndexed { index, operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    // 归一化目标节点标识后再做去重和存在性判断。
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
                    // 节点删除可能展开为多个目标，需要逐个比对当前已知集合。
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
                    // 先对边标识归一化，再检查端点节点是否存在。
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
                    // 任意一个端点不在当前已知节点集合中，都视为无效边。
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
                    // 边的删除同样可能展开为多个目标边标识。
                    val edgeIds = resolution.edgeRemovalIds(operation.edgeId)
                    // 仅保留实际存在的目标，剩余用于反馈缺失信息。
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

    /**
     * 组装一条带操作序号和目标标识的图编辑问题，默认不可重试。
     */
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
