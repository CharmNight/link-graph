package com.charmnight.linkgraph.sync

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation

/**
 * 把草稿 patch 应用到当前工作图。
 * 一期只处理图层内的节点/边增删改，不直接落代码。
 */
class GraphPatchApplyService {
    /**
     * 把图补丁应用到当前工作图，可选只应用指定操作。
     */
    fun apply(
        graph: GraphDocument,
        patch: GraphPatch,
        operationIds: Set<String>? = null,
    ): GraphDocument {
        // 先筛出真正要执行的操作，支持只预览或应用部分补丁。
        val selectedOperations = patch.operations.filter { operationIds == null || it.id in operationIds }
        // 使用可变映射承接中间状态，便于按操作顺序增删改节点和边。
        var workingNodes = graph.nodes.associateBy { it.id }.toMutableMap()
        var workingEdges = graph.edges.associateBy { it.id }.toMutableMap()

        selectedOperations.forEach { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_NODE,
                GraphPatchAction.UPDATE_NODE,
                GraphPatchAction.ADD_ANNOTATION,
                GraphPatchAction.MARK_UNCERTAIN
                -> operation.node?.let { node ->
                    // 节点新增与更新统一走节点合并逻辑。
                    workingNodes[node.id] = mergeNode(workingNodes[node.id], node, operation)
                }

                GraphPatchAction.DELETE_NODE -> {
                    // 删除节点时顺带移除所有连到该节点的边。
                    workingNodes.remove(operation.elementId)
                    workingEdges.values
                        .filter { edge -> edge.fromNodeId == operation.elementId || edge.toNodeId == operation.elementId }
                        .forEach { edge -> workingEdges.remove(edge.id) }
                }

                GraphPatchAction.ADD_EDGE,
                GraphPatchAction.UPDATE_EDGE
                -> operation.edge?.let { edge ->
                    // 边新增与更新统一走边合并逻辑。
                    workingEdges[edge.id] = mergeEdge(workingEdges[edge.id], edge, operation)
                }

                GraphPatchAction.DELETE_EDGE -> {
                    workingEdges.remove(operation.elementId)
                }
            }
        }

        // 返回排序后的新图，并清空已消费的补丁载荷。
        return graph.copy(
            nodes = workingNodes.values.sortedBy { it.id },
            edges = workingEdges.values.sortedBy { it.id },
            patch = null,
        )
    }

    /**
     * 合并节点变更。
     */
    private fun mergeNode(
        existing: GraphNode?,
        incoming: GraphNode,
        operation: GraphPatchOperation,
    ): GraphNode {
        // 新增节点或旧节点不存在时，直接使用传入节点。
        if (existing == null || operation.action == GraphPatchAction.ADD_NODE) {
            return incoming
        }
        // 更新节点时保留旧值中的缺省信息，同时覆盖显式变更字段。
        return existing.copy(
            type = incoming.type,
            title = incoming.title,
            location = incoming.location ?: existing.location,
            signature = incoming.signature ?: existing.signature,
            inputs = incoming.inputs.ifEmpty { existing.inputs },
            outputs = incoming.outputs.ifEmpty { existing.outputs },
            doc = incoming.doc ?: existing.doc,
            sourceKind = incoming.sourceKind ?: existing.sourceKind,
            status = incoming.status ?: existing.status,
            bindingStatus = incoming.bindingStatus,
            certainty = incoming.certainty,
            diff = incoming.diff,
            evidence = if (incoming.evidence.isEmpty()) existing.evidence else incoming.evidence,
            uncertainty = incoming.uncertainty ?: existing.uncertainty,
            metadata = existing.metadata + incoming.metadata,
            sourceTag = incoming.sourceTag,
        )
    }

    /**
     * 合并边变更。
     */
    private fun mergeEdge(
        existing: GraphEdge?,
        incoming: GraphEdge,
        operation: GraphPatchOperation,
    ): GraphEdge {
        // 新增边或旧边不存在时，直接使用传入边。
        if (existing == null || operation.action == GraphPatchAction.ADD_EDGE) {
            return incoming
        }
        // 更新边时沿用已有补充信息，并覆盖结构字段与显式元数据。
        return existing.copy(
            type = incoming.type,
            fromNodeId = incoming.fromNodeId,
            toNodeId = incoming.toNodeId,
            label = incoming.label ?: existing.label,
            certainty = incoming.certainty,
            bindingStatus = incoming.bindingStatus,
            status = incoming.status ?: existing.status,
            diff = incoming.diff,
            evidence = if (incoming.evidence.isEmpty()) existing.evidence else incoming.evidence,
            uncertainty = incoming.uncertainty ?: existing.uncertainty,
            metadata = existing.metadata + incoming.metadata,
            sourceTag = incoming.sourceTag,
        )
    }
}
