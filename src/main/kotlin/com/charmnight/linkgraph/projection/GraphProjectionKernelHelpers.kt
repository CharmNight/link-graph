package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * GraphProjectionKernel 的纯展示 / 优先级计算 helper（P2-1 拆分）。
 *
 * 这些函数无状态、无副作用、不持有 graph 状态，与方向遍历 / 锚点选择 / 溢出折叠
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
