package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType

/**
 * GraphProjectionKernel 的纯展示 / 优先级计算辅助函数（P2-1 拆分）。
 *
 * 这些函数无状态、无副作用、不持有图状态，与方向遍历 / 锚点选择 / 溢出折叠
 * 主流程解耦后便于复用与单独测试。
 */

/** 读取根调用顺序；不存在时返回 null。 */
internal fun rootCallOrder(edge: GraphEdge): Int? = edge.metadata["callOrder"]?.toIntOrNull()

/** 判断节点是否已经是提取阶段生成的溢出摘要节点。 */
internal fun isExtractionOverflowNode(node: GraphNode?): Boolean {
    return node?.metadata?.containsKey(GraphProjectionMetadata.Overflow.HIDDEN_METHOD_COUNT) == true &&
        node.metadata.containsKey(GraphProjectionMetadata.Overflow.TITLE_PREFIX)
}

/**
 * 计算节点在展示中的优先级（数值越小越优先保留）。
 *
 * - FLOW_SCOPE / FLOW_ACTION / TERMINAL / MERGE：高优先级（流程核心）
 * - METHOD / 资源类节点（SQL / HTTP / FEIGN 等）：中优先级
 * - 类型 / 包 / 配置等结构节点：低优先级
 * - 访问器（getter/setter）和 UNCERTAIN_LINK：最低优先级
 */
internal fun nodePriority(node: GraphNode?): Int {
    if (node == null) {
        return 4
    }
    if (isAccessorLike(node)) {
        return 4
    }
    return when (node.type) {
        NodeType.FLOW_SCOPE -> 0
        NodeType.FLOW_ACTION -> 1
        NodeType.TERMINAL,
        NodeType.MERGE -> 1
        NodeType.METHOD -> 2
        NodeType.SQL,
        NodeType.HTTP_ENDPOINT,
        NodeType.FEIGN_CLIENT,
        NodeType.DUBBO_SERVICE,
        NodeType.MQ_TOPIC,
        NodeType.MQ_CONSUMER -> 2
        NodeType.CLASS,
        NodeType.MODULE,
        NodeType.PACKAGE,
        NodeType.INTERFACE,
        NodeType.ENUM,
        NodeType.ANNOTATION,
        NodeType.RECORD,
        NodeType.OBJECT,
        NodeType.EXTERNAL_CLASS,
        NodeType.LIBRARY,
        NodeType.SERVICE,
        NodeType.COMPONENT,
        NodeType.LAYER,
        NodeType.RESOURCE,
        NodeType.CONFIG_ITEM,
        NodeType.XML_RESOURCE,
        NodeType.DOC_PAGE -> 3
        NodeType.UNCERTAIN_LINK -> 4
    }
}

/** 计算边在方向遍历中的综合优先级（结构优先级 × 10 + 端点节点优先级最大值）。 */
internal fun edgePriority(
    edge: GraphEdge,
    nodeById: Map<String, GraphNode>,
): Int {
    val structuralPriority = when (edge.type) {
        EdgeType.CONTAINS_FLOW -> 0
        EdgeType.CALL -> 1
        EdgeType.ROUTES_TO -> 2
        EdgeType.PUBLISHES_TO, EdgeType.CONSUMES_FROM -> 3
        else -> 4
    }
    val endpointPriority = maxOf(
        nodePriority(nodeById[edge.fromNodeId]),
        nodePriority(nodeById[edge.toNodeId]),
    )
    return structuralPriority * 10 + endpointPriority
}

/** 返回方向遍历时使用的边排序器：先按 edgePriority，再按 callOrder，再按 id。 */
internal fun edgeComparator(nodeById: Map<String, GraphNode>): Comparator<GraphEdge> {
    return compareBy(
        { edgePriority(it, nodeById) },
        { rootCallOrder(it) ?: Int.MAX_VALUE },
        { it.id },
    )
}

/** 返回锚点一跳邻居选择时使用的边排序器：先按 callOrder，再按 edgePriority，再按 id。 */
internal fun rootEdgeComparator(nodeById: Map<String, GraphNode>): Comparator<GraphEdge> {
    return compareBy(
        { rootCallOrder(it) ?: Int.MAX_VALUE },
        { edgePriority(it, nodeById) },
        { it.id },
    )
}

/**
 * 判断方法节点是否更像 getter/setter 等访问器。
 *
 * 匹配规则：get/is/has 开头且无参，或 set 开头且最多 1 参。访问器节点优先级最低，
 * 避免在窗口溢出时占用关键节点的展示空间。
 */
internal fun isAccessorLike(node: GraphNode): Boolean {
    if (node.type != NodeType.METHOD) {
        return false
    }
    val methodName = node.title.substringAfterLast('.')
    val parameterText = node.signature.orEmpty().substringAfter('(', "").substringBefore(')', "")
    val parameterCount = parameterText
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .size
    val getterLike =
        (methodName.startsWith("get") && methodName.length > 3 && parameterCount == 0) ||
            (methodName.startsWith("is") && methodName.length > 2 && parameterCount == 0) ||
            (methodName.startsWith("has") && methodName.length > 3 && parameterCount == 0)
    val setterLike = methodName.startsWith("set") && methodName.length > 3 && parameterCount <= 1
    return getterLike || setterLike
}

/**
 * 折叠摘要节点的方向类型。
 *
 * 抽到顶层（原为 GraphProjectionKernel 内私有枚举），让 [overflowNode]
 * 辅助函数也能引用。
 */
internal enum class OverflowDirection(val label: String) {
    UPSTREAM("上游"),
    DOWNSTREAM("下游"),
}

/** 返回边的"邻居"端点 ID：若 from 是 nodeId 则返回 to，否则返回 from。 */
internal fun GraphEdge.neighborOf(nodeId: String): String =
    if (fromNodeId == nodeId) toNodeId else fromNodeId

/** 计算节点在跨方法扩展遍历中的优先级（当前方法节点减 10，锚点减 5）。 */
internal fun traversalNodePriority(
    nodeId: String,
    nodeById: Map<String, GraphNode>,
    currentMethodNodeIds: Set<String>,
    anchorNodeId: String? = null,
    methodBodyNodeIds: Set<String>? = null,
): Int {
    val basePriority = nodePriority(nodeById[nodeId])
    if (nodeId == anchorNodeId) return basePriority - 10
    if (methodBodyNodeIds != null && nodeId in methodBodyNodeIds) return basePriority - 5
    if (nodeId in currentMethodNodeIds) return basePriority
    return basePriority + 10
}

/** 判断边是否属于方法体结构性流程边（CONTAINS_FLOW + 邻居是 FLOW_SCOPE）。 */
internal fun isStructuralFlowEdge(edge: GraphEdge, neighborNode: GraphNode?): Boolean =
    edge.type == EdgeType.CONTAINS_FLOW && neighborNode?.type == NodeType.FLOW_SCOPE

/** 计算锚点的初始遍历深度：结构性流程边起点为 0，否则为 1。 */
internal fun seedDepthFromAnchor(edge: GraphEdge, neighborNode: GraphNode?): Int =
    if (isStructuralFlowEdge(edge, neighborNode)) 0 else 1

/** 计算沿边继续遍历后的深度：结构性流程边保持当前深度，否则 +1。 */
internal fun nextTraversalDepth(currentDepth: Int, edge: GraphEdge, neighborNode: GraphNode?): Int =
    if (isStructuralFlowEdge(edge, neighborNode)) currentDepth else currentDepth + 1

/**
 * 判断是否应抑制跨方法体展开。
 *
 * 规则：锚点是 METHOD 类型、当前节点是 METHOD 但不在锚点方法体内、邻居是 FLOW_ACTION/FLOW_SCOPE 时
 * 抑制展开（避免相邻方法的内部流程节点污染当前方法视图）。
 */
internal fun shouldSuppressCrossMethodBodyExpansion(
    anchorNodeType: NodeType?,
    currentNode: GraphNode?,
    neighborNode: GraphNode?,
    anchorMethodBodyNodeIds: Set<String>,
): Boolean {
    if (anchorNodeType != NodeType.METHOD) {
        return false
    }
    if (currentNode == null || neighborNode == null) {
        return false
    }
    if (currentNode.id in anchorMethodBodyNodeIds) {
        return false
    }
    if (currentNode.type != NodeType.METHOD) {
        return false
    }
    return neighborNode.type == NodeType.FLOW_ACTION || neighborNode.type == NodeType.FLOW_SCOPE
}

/**
 * BFS 收集锚点方法及其直接流程体内的节点 ID。
 *
 * 沿 CONTAINS_FLOW → FLOW_SCOPE 展开，沿 CALL → 非 UNCERTAIN_LINK 节点；FLOW_ACTION 节点继续展开。
 */
internal fun collectCurrentMethodNodeIds(
    anchorNodeId: String,
    outgoingBySource: Map<String, List<GraphEdge>>,
    nodeById: Map<String, GraphNode>,
): Set<String> {
    val currentMethodNodeIds = linkedSetOf(anchorNodeId)
    val queue = ArrayDeque<String>().apply { add(anchorNodeId) }
    while (queue.isNotEmpty()) {
        val sourceNodeId = queue.removeFirst()
        outgoingBySource[sourceNodeId]
            .orEmpty()
            .sortedWith(rootEdgeComparator(nodeById))
            .forEach { edge ->
                val targetNode = nodeById[edge.toNodeId] ?: return@forEach
                when {
                    edge.type == EdgeType.CONTAINS_FLOW && targetNode.type == NodeType.FLOW_SCOPE -> {
                        if (currentMethodNodeIds.add(targetNode.id)) {
                            queue += targetNode.id
                        }
                    }
                    edge.type == EdgeType.CALL && targetNode.type != NodeType.UNCERTAIN_LINK -> {
                        if (currentMethodNodeIds.add(targetNode.id) && targetNode.type == NodeType.FLOW_ACTION) {
                            queue += targetNode.id
                        }
                    }
                }
            }
    }
    return currentMethodNodeIds
}

/**
 * BFS 收集锚点方法本体中的流程节点（不跨出方法体）。
 *
 * 仅沿 CONTAINS_FLOW → FLOW_SCOPE 与 CALL → FLOW_ACTION 展开，比 [collectCurrentMethodNodeIds]
 * 更严格（后者还会保留非 FLOW_ACTION 的 CALL 目标作为入集合但不展开）。
 */
internal fun collectAnchorMethodBodyNodeIds(
    anchorNodeId: String,
    outgoingBySource: Map<String, List<GraphEdge>>,
    nodeById: Map<String, GraphNode>,
): Set<String> {
    val bodyNodeIds = linkedSetOf(anchorNodeId)
    val queue = ArrayDeque<String>().apply { add(anchorNodeId) }
    while (queue.isNotEmpty()) {
        val sourceNodeId = queue.removeFirst()
        outgoingBySource[sourceNodeId]
            .orEmpty()
            .sortedWith(rootEdgeComparator(nodeById))
            .forEach { edge ->
                val targetNode = nodeById[edge.toNodeId] ?: return@forEach
                when {
                    edge.type == EdgeType.CONTAINS_FLOW && targetNode.type == NodeType.FLOW_SCOPE -> {
                        if (bodyNodeIds.add(targetNode.id)) {
                            queue += targetNode.id
                        }
                    }
                    edge.type == EdgeType.CALL && targetNode.type == NodeType.FLOW_ACTION -> {
                        if (bodyNodeIds.add(targetNode.id)) {
                            queue += targetNode.id
                        }
                    }
                }
            }
    }
    return bodyNodeIds
}

/**
 * 创建上下游折叠摘要节点（GraphNode）。
 *
 * 用于交互窗口里把被折叠的 N 个节点表示为一个 UNCERTAIN_LINK 类型的占位节点，
 * 标注方向（UPSTREAM / DOWNSTREAM）、折叠统计与是否可继续展开。
 */
internal fun overflowNode(
    anchorNodeId: String,
    direction: OverflowDirection,
    hiddenNodeCount: Int,
    hiddenEdgeCount: Int,
    hiddenCurrentMethodNodeCount: Int,
    hiddenCrossMethodNodeCount: Int,
    boundaryNodeCount: Int = 0,
    expandableNodeCount: Int = hiddenNodeCount,
) = GraphNode(
    id = GraphNode.stableId(
        NodeType.UNCERTAIN_LINK,
        "$anchorNodeId-${direction.name.lowercase()}-overflow",
        "interactive",
    ),
    type = NodeType.UNCERTAIN_LINK,
    title = "${direction.label}已折叠 $hiddenNodeCount 个节点",
    signature = "另有 $hiddenEdgeCount 条链路未在当前交互窗口展开",
    doc = "完整事实图仍保留在后台，可继续问答、导出 Mermaid，或切换到其他方法重新聚焦。",
    certainty = Certainty.RULE_INFERRED,
    bindingStatus = BindingStatus.PARTIALLY_SYNCED,
    sourceTag = GraphSourceTag.UNCERTAIN_FACT,
    metadata = mapOf(
        GraphProjectionMetadata.Overflow.DIRECTION to direction.name,
        GraphProjectionMetadata.Hidden.NODE_COUNT to hiddenNodeCount.toString(),
        GraphProjectionMetadata.Hidden.EDGE_COUNT to hiddenEdgeCount.toString(),
        GraphProjectionMetadata.Hidden.CURRENT_METHOD_NODE_COUNT to hiddenCurrentMethodNodeCount.toString(),
        GraphProjectionMetadata.Hidden.CROSS_METHOD_NODE_COUNT to hiddenCrossMethodNodeCount.toString(),
        GraphProjectionMetadata.Overflow.BOUNDARY_NODE_COUNT to boundaryNodeCount.toString(),
        GraphProjectionMetadata.Overflow.EXPANDABLE_NODE_COUNT to expandableNodeCount.toString(),
        GraphProjectionMetadata.Overflow.PRESENTATION to if (expandableNodeCount == 0 && boundaryNodeCount > 0) {
            "METHOD_BOUNDARY"
        } else {
            "EXPANDABLE"
        },
    ),
)
