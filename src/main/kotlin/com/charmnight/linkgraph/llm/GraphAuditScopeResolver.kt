package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

/**
 * 负责计算图问答时实际参与分析的节点与边范围。
 */
internal object GraphAuditScopeResolver {
    /**
     * 选择问答时优先使用的图。
     */
    private fun scopeGraph(context: GraphAuditContext): com.charmnight.linkgraph.model.GraphDocument {
        // 问答范围与 patch 落点一律基于当前可编辑图；缺省时再回退到事实图。
        return context.editableGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: context.factGraph
    }

    /**
     * 构建当前作用域内的节点索引。
     */
    private fun scopeNodeById(context: GraphAuditContext): LinkedHashMap<String, GraphNode> {
        return linkedMapOf<String, GraphNode>().apply {
            // 使用有序映射保存节点，便于后续按加入顺序返回。
            scopeGraph(context).nodes.forEach { node -> put(node.id, node) }
        }
    }

    /**
     * 获取当前作用域图中的全部边。
     */
    private fun scopeEdges(context: GraphAuditContext): List<GraphEdge> {
        return scopeGraph(context).edges
    }

    /**
     * 解析问答范围内的节点集合。
     */
    fun resolveScopeNodes(context: GraphAuditContext): List<GraphNode> {
        // 未选中节点时，直接使用整个作用域图。
        val currentScopeGraph = scopeGraph(context)
        if (context.selectedNodeIds.isEmpty()) {
            return currentScopeGraph.nodes
        }
        // 先建立节点索引和选中集合，便于后续扩展邻接节点。
        val nodeById = scopeNodeById(context)
        val selectedNodeIds = context.selectedNodeIds.toSet()
        val scopeNodeIds = linkedSetOf<String>()
        context.selectedNodeIds.forEach { nodeId ->
            if (nodeById.containsKey(nodeId)) {
                scopeNodeIds += nodeId
            }
        }
        // 只要边与选中节点相连，就把边两端节点都纳入问答范围。
        scopeEdges(context)
            .filter { edge -> edge.fromNodeId in selectedNodeIds || edge.toNodeId in selectedNodeIds }
            .forEach { edge ->
                scopeNodeIds += edge.fromNodeId
                scopeNodeIds += edge.toNodeId
            }

        // 最终按照收集顺序返回去重后的节点列表。
        return scopeNodeIds
            .mapNotNull(nodeById::get)
            .distinctBy(GraphNode::id)
    }

    /**
     * 根据节点范围解析对应的边集合。
     */
    fun resolveScopeEdges(
        context: GraphAuditContext,
        scopeNodes: List<GraphNode> = resolveScopeNodes(context),
    ): List<GraphEdge> {
        // 先把作用域节点转成集合，后续做边过滤。
        val scopeNodeIds = scopeNodes.mapTo(linkedSetOf(), GraphNode::id)
        if (scopeNodeIds.isEmpty()) {
            return emptyList()
        }
        // 仅保留起点和终点都位于作用域中的边。
        return scopeEdges(context)
            .filter { edge -> edge.fromNodeId in scopeNodeIds && edge.toNodeId in scopeNodeIds }
    }
}
