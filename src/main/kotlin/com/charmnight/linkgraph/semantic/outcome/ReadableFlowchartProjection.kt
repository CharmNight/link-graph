package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphProvenance

/** 节点元数据中记录"投影后合并自的原始节点 ID 列表"的字段名。 */
private const val FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds"
/** 节点/边元数据中记录当前投影模式的字段名。 */
private const val FLOWCHART_PROJECTION_MODE_KEY = "flowchart.projection.mode"
/** 合并后节点保存"原始动作节点标题"的字段名。 */
private const val FLOWCHART_PROJECTED_ACTION_TITLE_KEY = "flowchart.projectedActionTitle"
/** 可读模式的标识值，用于在投影产物上标注当前模式。 */
private const val FLOWCHART_READABLE_MODE = "READABLE"

/**
 * 生成可读模式下的流程图视图。
 *
 * 可读模式会折叠冗余的卫语句与子程序调用链，让流程图更贴近用户直觉；
 * 同时保留完整图与投影索引，便于回退与编辑映射。
 */
internal fun projectReadableFlowchartView(
    graph: GraphDocument,
    anchorNodeId: String?,
): FlowchartViewDocument {
    /** 经过可读模式折叠后的可见图。 */
    val simplifiedVisibleGraph = projectReadableFlowchartGraph(graph)
    /** 在折叠后的可见图中重新解析锚点 ID，避免锚点节点已被合并导致找不到。 */
    val resolvedAnchorNodeId = resolveProjectedFlowchartNodeId(simplifiedVisibleGraph, anchorNodeId)
        ?: simplifiedVisibleGraph.nodes.firstOrNull()?.id
    return FlowchartViewDocument(
        visibleGraph = simplifiedVisibleGraph,
        fullGraph = graph,
        anchorNodeId = resolvedAnchorNodeId,
        summary = deriveFlowchartSummary(
            visibleGraph = simplifiedVisibleGraph,
            fullGraph = graph,
        ),
        projectionIndex = graphProjectionIndexForVisibleGraph(
            visibleGraph = simplifiedVisibleGraph,
            fullGraph = graph,
        ),
    )
}

/**
 * 在折叠后的可见图中解析与请求 ID 对应的实际节点 ID。
 *
 * 如果请求的 ID 直接存在于可见图中则返回；否则在合并节点的别名列表中查找，
 * 让请求指向已被合并的旧 ID 时仍能定位到当前节点。
 */
internal fun resolveProjectedFlowchartNodeId(
    graph: GraphDocument,
    requestedNodeId: String?,
): String? {
    /** 去除空白后的请求 ID，空字符串视为没有请求。 */
    val normalizedRequestedNodeId = requestedNodeId?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (graph.nodes.any { node -> node.id == normalizedRequestedNodeId }) {
        return normalizedRequestedNodeId
    }
    return graph.nodes.firstOrNull { node ->
        projectedAliasNodeIds(node).contains(normalizedRequestedNodeId)
    }?.id
}

/**
 * 执行可读模式的流程图折叠。
 *
 * 主要做两类折叠：
 * 1. 决策节点前的卫语句（condition action / 子程序调用）合并到决策节点上；
 * 2. 普通动作节点之后连续的子程序调用合并到该动作节点上。
 * 折叠后会重写边的起止节点，去掉自环与重复边。
 */
private fun projectReadableFlowchartGraph(graph: GraphDocument): GraphDocument {
    if (!looksLikeFlowchartGraph(graph)) {
        return graph
    }

    /** 节点 ID 到节点实例的索引，便于在折叠过程中按 ID 查询。 */
    val nodesById = graph.nodes.associateBy(GraphNode::id)
    /** 当前图中所有控制流相关边（CONTROL_FLOW 与 CONTAINS_FLOW）。 */
    val controlFlowEdges = graph.edges.filter { edge -> edge.type == EdgeType.CONTROL_FLOW || edge.type == EdgeType.CONTAINS_FLOW }
    /** 按起点节点 ID 分组的出边索引。 */
    val outgoingControlFlowEdges = controlFlowEdges.groupBy(GraphEdge::fromNodeId)
    /** 按终点节点 ID 分组的入边索引。 */
    val incomingControlFlowEdges = controlFlowEdges.groupBy(GraphEdge::toNodeId)
    /** 与控制流相关或需要保留的节点 ID 集合。 */
    val flowRelevantNodeIds = linkedSetOf<String>().apply {
        controlFlowEdges.forEach { edge ->
            add(edge.fromNodeId)
            add(edge.toNodeId)
        }
        graph.nodes
            .filter { node ->
                node.metadata["flowchart.kind"] != null ||
                    node.metadata["flow.kind"] != null ||
                    shouldRetainEditableNodeInReadableFlowchart(node)
            }
            .mapTo(this) { node -> node.id }
    }
    /** 被合并节点的重定向映射：被合并节点 ID -> 保留节点 ID。 */
    val redirectedNodeIds = linkedMapOf<String, String>()
    /** 保留节点 ID -> 合并自的原始节点 ID 集合。 */
    val aliasNodeIdsByRetainedNodeId = linkedMapOf<String, LinkedHashSet<String>>()

    graph.nodes
        .filter(::isDecisionNode)
        .forEach { decisionNode ->
            /** 当前决策节点之前可以被合并的卫语句节点 ID 列表。 */
            val removableGuardNodeIds = collectGuardProjectionNodeIds(
                decisionNodeId = decisionNode.id,
                nodesById = nodesById,
                outgoingControlFlowEdges = outgoingControlFlowEdges,
                incomingControlFlowEdges = incomingControlFlowEdges,
                redirectedNodeIds = redirectedNodeIds,
            )
            if (removableGuardNodeIds.isEmpty()) {
                return@forEach
            }
            removableGuardNodeIds.forEach { removableNodeId ->
                redirectedNodeIds[removableNodeId] = decisionNode.id
            }
            aliasNodeIdsByRetainedNodeId
                .getOrPut(decisionNode.id) { linkedSetOf() }
                .addAll(removableGuardNodeIds)
        }

    graph.nodes
        .filter(::isReadableActionNode)
        .forEach { actionNode ->
            if (actionNode.id in redirectedNodeIds) {
                return@forEach
            }
            /** 当前动作节点之后可以被合并的连续子程序调用节点 ID 列表。 */
            val removableInvocationNodeIds = collectInvocationProjectionNodeIds(
                actionNodeId = actionNode.id,
                nodesById = nodesById,
                outgoingControlFlowEdges = outgoingControlFlowEdges,
                incomingControlFlowEdges = incomingControlFlowEdges,
                redirectedNodeIds = redirectedNodeIds,
            )
            if (removableInvocationNodeIds.isEmpty()) {
                return@forEach
            }
            removableInvocationNodeIds.forEach { removableNodeId ->
                redirectedNodeIds[removableNodeId] = actionNode.id
            }
            aliasNodeIdsByRetainedNodeId
                .getOrPut(actionNode.id) { linkedSetOf() }
                .addAll(removableInvocationNodeIds)
        }

    if (redirectedNodeIds.isEmpty()) {
        return graph.copy(
            nodes = graph.nodes
                .filter { node -> node.id in flowRelevantNodeIds }
                .map { node ->
                    if (shouldMarkReadableProjectionNode(node)) {
                        markReadableProjectionNode(node)
                    } else {
                        node
                    }
                },
            edges = controlFlowEdges.map { edge ->
                edge.copy(metadata = edge.metadata + mapOf(FLOWCHART_PROJECTION_MODE_KEY to FLOWCHART_READABLE_MODE))
            },
        )
    }

    /** 折叠后保留的节点列表，合并了别名信息的节点会带上别名元数据。 */
    val retainedNodes = graph.nodes.mapNotNull { node ->
        if (node.id in redirectedNodeIds) {
            return@mapNotNull null
        }
        if (node.id !in flowRelevantNodeIds) {
            return@mapNotNull null
        }
        val aliasNodeIds = aliasNodeIdsByRetainedNodeId[node.id].orEmpty()
        if (aliasNodeIds.isEmpty()) {
            if (shouldMarkReadableProjectionNode(node)) {
                markReadableProjectionNode(node)
            } else {
                node
            }
        } else {
            val aliasedNodes = aliasNodeIds.mapNotNull(nodesById::get)
            val projectedNode = mergeReadableProjectedNode(
                retainedNode = node,
                aliasedNodes = aliasedNodes,
            )
            markReadableProjectionNode(
                projectedNode.copy(
                    metadata = projectedNode.metadata + mapOf(
                        FLOWCHART_ALIAS_IDS_KEY to aliasNodeIds.joinToString(","),
                    ),
                ),
            )
        }
    }
    /** 折叠后保留的节点 ID 集合，用于过滤边。 */
    val retainedNodeIds = retainedNodes.mapTo(linkedSetOf()) { node -> node.id }
    /** 折叠后保留的边，按起止节点和标签去重，避免重复连线。 */
    val projectedEdges = linkedMapOf<String, GraphEdge>()
    controlFlowEdges.forEach { edge ->
        /** 边起点经过折叠后的实际节点 ID。 */
        val projectedFromNodeId = resolveProjectedNodeId(edge.fromNodeId, redirectedNodeIds)
        /** 边终点经过折叠后的实际节点 ID。 */
        val projectedToNodeId = resolveProjectedNodeId(edge.toNodeId, redirectedNodeIds)
        if (projectedFromNodeId == projectedToNodeId) {
            return@forEach
        }
        if (projectedFromNodeId !in retainedNodeIds || projectedToNodeId !in retainedNodeIds) {
            return@forEach
        }
        val projectedEdge = edge.copy(
            id = GraphEdge.stableId(
                type = edge.type,
                fromNodeId = projectedFromNodeId,
                toNodeId = projectedToNodeId,
                ownerContext = edge.label ?: edge.id,
            ),
            fromNodeId = projectedFromNodeId,
            toNodeId = projectedToNodeId,
            metadata = edge.metadata + mapOf(FLOWCHART_PROJECTION_MODE_KEY to FLOWCHART_READABLE_MODE),
        )
        /** 用于在合并后去重的边的复合键。 */
        val dedupeKey = listOf(
            projectedEdge.type.name,
            projectedEdge.fromNodeId,
            projectedEdge.toNodeId,
            projectedEdge.label.orEmpty(),
        ).joinToString("|")
        projectedEdges.putIfAbsent(dedupeKey, projectedEdge)
    }

    return GraphDocument(
        nodes = retainedNodes,
        edges = projectedEdges.values.toList(),
        patch = graph.patch,
    )
}

/** 判断当前图是否携带流程图相关元数据，决定是否需要进入可读模式折叠。 */
private fun looksLikeFlowchartGraph(graph: GraphDocument): Boolean {
    return graph.nodes.any { node ->
        node.metadata["flowchart.kind"] != null || node.metadata["flow.kind"] != null
    }
}

/** 判断节点是否为决策节点（用于卫语句折叠）。 */
private fun isDecisionNode(node: GraphNode): Boolean = resolveFlowchartKind(node) == "DECISION"

/** 判断节点是否为可参与折叠的普通动作节点，排除调用与条件类节点。 */
private fun isReadableActionNode(node: GraphNode): Boolean {
    if (resolveFlowchartKind(node) != "PROCESS") {
        return false
    }
    val flowKind = node.metadata["flow.kind"]
    return flowKind != "INVOCATION" && flowKind != "CONDITION"
}

/** 读取节点元数据中保存的别名 ID 集合。 */
private fun projectedAliasNodeIds(node: GraphNode): Set<String> {
    return node.metadata[FLOWCHART_ALIAS_IDS_KEY]
        ?.split(',')
        ?.mapNotNull { aliasNodeId -> aliasNodeId.trim().takeIf(String::isNotBlank) }
        ?.toSet()
        .orEmpty()
}

/** 判断节点是否为用户手工编辑的草稿节点，这类节点需要保留可编辑性。 */
private fun shouldRetainEditableNodeInReadableFlowchart(node: GraphNode): Boolean {
    return node.provenance == GraphProvenance.USER_DRAFT
}

/** 判断节点是否需要打上可读投影模式的标记。 */
private fun shouldMarkReadableProjectionNode(node: GraphNode): Boolean {
    return node.metadata["flowchart.kind"] != null || node.metadata["flow.kind"] != null
}

/** 给节点追加可读投影模式的元数据标记。 */
private fun markReadableProjectionNode(node: GraphNode): GraphNode {
    return node.copy(
        metadata = node.metadata + mapOf(FLOWCHART_PROJECTION_MODE_KEY to FLOWCHART_READABLE_MODE),
    )
}

/**
 * 把多个被合并的节点融合到保留节点上。
 *
 * 如果被合并节点中存在子程序调用节点，则用该节点的标题/签名/文档覆盖保留节点，
 * 同时把保留节点的原始标题保存到 projectedActionTitle 字段中，便于回退。
 */
private fun mergeReadableProjectedNode(
    retainedNode: GraphNode,
    aliasedNodes: List<GraphNode>,
): GraphNode {
    /** 被合并节点中的子程序调用节点，优先取它的展示信息。 */
    val projectedInvocation = aliasedNodes.firstOrNull { node ->
        node.metadata["flow.kind"] == "INVOCATION" && node.metadata["flowchart.kind"] == "SUBROUTINE"
    } ?: return retainedNode
    return retainedNode.copy(
        title = projectedInvocation.title,
        signature = projectedInvocation.signature ?: retainedNode.signature,
        doc = projectedInvocation.doc ?: retainedNode.doc,
        metadata = retainedNode.metadata + mapOf(
            FLOWCHART_PROJECTED_ACTION_TITLE_KEY to retainedNode.title,
        ),
    )
}

/**
 * 沿入边方向回溯，收集可以合并到决策节点上的卫语句节点 ID。
 *
 * 仅当回溯路径以 condition action 结尾时才视为有效卫语句，返回按原始顺序排列的可合并节点。
 */
private fun collectGuardProjectionNodeIds(
    decisionNodeId: String,
    nodesById: Map<String, GraphNode>,
    outgoingControlFlowEdges: Map<String, List<GraphEdge>>,
    incomingControlFlowEdges: Map<String, List<GraphEdge>>,
    redirectedNodeIds: Map<String, String>,
): List<String> {
    /** 当前回溯过程中收集到的可合并节点 ID。 */
    val removableNodeIds = mutableListOf<String>()
    /** 当前回溯光标所在的节点 ID。 */
    var cursorNodeId = decisionNodeId
    /** 是否在回溯链中遇到了真正的 condition action，决定是否最终接受这组折叠。 */
    var sawConditionAction = false

    while (true) {
        /** 当前光标节点的入边，仅保留无标签（普通顺序流）的边。 */
        val incomingEdges = incomingControlFlowEdges[cursorNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (incomingEdges.size != 1) {
            break
        }
        /** 上一节点的 ID。 */
        val previousNodeId = incomingEdges.single().fromNodeId
        if (previousNodeId in redirectedNodeIds) {
            break
        }
        /** 上一节点实例。 */
        val previousNode = nodesById[previousNodeId] ?: break
        /** 上一节点的出边，仅保留无标签的边。 */
        val outgoingEdges = outgoingControlFlowEdges[previousNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (outgoingEdges.size != 1 || outgoingEdges.single().toNodeId != cursorNodeId) {
            break
        }
        when {
            previousNode.metadata["flow.kind"] == "INVOCATION" && previousNode.metadata["flowchart.kind"] == "SUBROUTINE" -> {
                removableNodeIds += previousNodeId
                cursorNodeId = previousNodeId
            }

            previousNode.metadata["flow.kind"] == "CONDITION" && previousNode.metadata["flowchart.kind"] == "PROCESS" -> {
                removableNodeIds += previousNodeId
                sawConditionAction = true
                break
            }

            else -> break
        }
    }

    return if (sawConditionAction) removableNodeIds.asReversed() else emptyList()
}

/**
 * 沿出边方向前进，收集可以合并到动作节点上的连续子程序调用节点 ID。
 *
 * 要求路径上每一段都是无标签的顺序流，且每段中间节点都是子程序调用节点。
 */
private fun collectInvocationProjectionNodeIds(
    actionNodeId: String,
    nodesById: Map<String, GraphNode>,
    outgoingControlFlowEdges: Map<String, List<GraphEdge>>,
    incomingControlFlowEdges: Map<String, List<GraphEdge>>,
    redirectedNodeIds: Map<String, String>,
): List<String> {
    /** 收集到的子程序调用节点 ID 列表。 */
    val removableInvocationNodeIds = mutableListOf<String>()
    /** 当前前进光标所在的节点 ID。 */
    var cursorNodeId = actionNodeId

    while (true) {
        /** 当前光标节点的出边，仅保留无标签的边。 */
        val outgoingEdges = outgoingControlFlowEdges[cursorNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (outgoingEdges.size != 1) {
            break
        }
        /** 下一节点的 ID。 */
        val nextNodeId = outgoingEdges.single().toNodeId
        if (nextNodeId in redirectedNodeIds) {
            break
        }
        /** 下一节点实例。 */
        val nextNode = nodesById[nextNodeId] ?: break
        if (nextNode.metadata["flow.kind"] != "INVOCATION" || nextNode.metadata["flowchart.kind"] != "SUBROUTINE") {
            break
        }
        /** 下一节点的入边，要求是顺序流单链。 */
        val incomingEdges = incomingControlFlowEdges[nextNodeId]
            .orEmpty()
            .filter { edge -> edge.label.isNullOrBlank() }
        if (incomingEdges.size != 1 || incomingEdges.single().fromNodeId != cursorNodeId) {
            break
        }
        removableInvocationNodeIds += nextNodeId
        cursorNodeId = nextNodeId
    }

    return removableInvocationNodeIds
}

/**
 * 沿重定向映射递归查找节点最终对应的保留节点 ID。
 *
 * 折叠过程可能级联（A 被合并到 B，B 又被合并到 C），该方法负责一路找到终点；
 * 同时通过已访问集合避免在异常环路上死循环。
 */
private fun resolveProjectedNodeId(
    nodeId: String,
    redirectedNodeIds: Map<String, String>,
): String {
    /** 当前正在解析的节点 ID。 */
    var currentNodeId = nodeId
    /** 已访问的中间节点 ID，用于检测并打断潜在的环。 */
    val visitedNodeIds = linkedSetOf<String>()
    while (true) {
        val redirectedNodeId = redirectedNodeIds[currentNodeId] ?: return currentNodeId
        if (!visitedNodeIds.add(redirectedNodeId)) {
            return redirectedNodeId
        }
        currentNodeId = redirectedNodeId
    }
}
