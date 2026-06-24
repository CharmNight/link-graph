package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * 计算链路讲解场景实际生效的证据边界。
 * 当外部已经预设过证据边界时直接透传，否则基于当前展示图与源码片段现场推导。
 */
fun GraphBeautificationContext.effectiveEvidenceProfile(): GraphEvidenceProfile {
    if (evidenceProfile.anchorNodeType != null || evidenceProfile.allowedExplanationModes.isNotEmpty()) {
        return evidenceProfile
    }
    return buildGraphEvidenceProfile(
        graph = presentationContext.graph,
        fullGraph = presentationContext.fullGraph,
        anchorNodeId = presentationContext.anchorNodeId,
        selectedNodeIds = presentationContext.selectedNodeIds,
        sourceContext = sourceContext + stepSourceContext,
    )
}

/**
 * 计算图问答场景实际生效的证据边界。
 * 当外部已经预设过证据边界时直接透传，否则基于当前可编辑图（或事实图）和源码片段推导。
 */
fun GraphQaContext.effectiveEvidenceProfile(): GraphEvidenceProfile {
    if (evidenceProfile.anchorNodeType != null || evidenceProfile.allowedExplanationModes.isNotEmpty()) {
        return evidenceProfile
    }
    val graph = editableGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: factGraph
    return buildGraphEvidenceProfile(
        graph = graph,
        fullGraph = factGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: graph,
        anchorNodeId = selectedNodeIds.firstOrNull(),
        selectedNodeIds = selectedNodeIds,
        sourceContext = sourceContext,
    )
}

/**
 * 基于当前图与选区构建一份完整的图证据边界。
 * 包含锚点类型、可用关系类型、是否具备方法调用证据、允许讲解模式、禁止声明、证据缺口和推荐下钻目标。
 */
fun buildGraphEvidenceProfile(
    graph: GraphDocument,
    fullGraph: GraphDocument = graph,
    anchorNodeId: String? = null,
    selectedNodeIds: List<String> = emptyList(),
    sourceContext: List<SourceSnippetContext> = emptyList(),
): GraphEvidenceProfile {
    val anchor = resolveEvidenceAnchor(graph, fullGraph, anchorNodeId, selectedNodeIds)
    val relationGraph = fullGraph.takeIf { it.edges.isNotEmpty() } ?: graph
    val anchorId = anchor?.id ?: anchorNodeId
    val incomingEdges = relationGraph.edges.filter { edge -> anchorId != null && edge.toNodeId == anchorId }
    val outgoingEdges = relationGraph.edges.filter { edge -> anchorId != null && edge.fromNodeId == anchorId }
    val relationKinds = (incomingEdges + outgoingEdges)
        .map { edge -> edge.metadata["jvm.relation.kind"] ?: edge.type.name }
        .distinct()
        .sorted()
    val hasMethodCallEvidence = (incomingEdges + outgoingEdges).any { edge ->
        edge.type == EdgeType.CALL || edge.metadata["jvm.relation.kind"] == "CALLS"
    }
    val hasSourceEvidence = sourceContext.any { snippet ->
        snippet.nodeId == anchorId || selectedNodeIds.contains(snippet.nodeId)
    }
    val hasPackageMemberEvidence = anchor?.metadata?.get("indexed.memberClassCount")?.toIntOrNull()?.let { it > 0 } == true ||
        anchor?.metadata?.get("architecture.sourceSample.count")?.toIntOrNull()?.let { it > 0 } == true
    val modes = allowedModesFor(anchor, hasMethodCallEvidence, relationKinds)
    val forbiddenClaims = forbiddenClaimsFor(anchor, hasMethodCallEvidence)
    val evidenceGaps = evidenceGapsFor(anchor, hasMethodCallEvidence, incomingEdges.size, outgoingEdges.size)
    return GraphEvidenceProfile(
        anchorNodeId = anchorId,
        anchorNodeType = anchor?.type,
        anchorArchitectureKind = anchor?.metadata?.get("architecture.node.kind"),
        availableRelationKinds = relationKinds,
        incomingRelationCount = incomingEdges.size,
        outgoingRelationCount = outgoingEdges.size,
        hasMethodCallEvidence = hasMethodCallEvidence,
        hasSourceEvidence = hasSourceEvidence,
        hasPackageMemberEvidence = hasPackageMemberEvidence,
        allowedExplanationModes = modes,
        forbiddenClaims = forbiddenClaims,
        evidenceGaps = evidenceGaps,
        recommendedDrilldowns = recommendedDrilldowns(graph, fullGraph, anchor),
    )
}

/**
 * 优先按锚点 ID、再按选区，最后回退到图中首个节点，解析问答或讲解使用的证据锚点节点。
 */
private fun resolveEvidenceAnchor(
    graph: GraphDocument,
    fullGraph: GraphDocument,
    anchorNodeId: String?,
    selectedNodeIds: List<String>,
): GraphNode? {
    val candidates = listOfNotNull(anchorNodeId) + selectedNodeIds
    return candidates.firstNotNullOfOrNull { id ->
        graph.nodes.firstOrNull { node -> node.id == id } ?: fullGraph.nodes.firstOrNull { node -> node.id == id }
    } ?: graph.nodes.firstOrNull() ?: fullGraph.nodes.firstOrNull()
}

/**
 * 根据锚点类型与关系证据，推导当前允许的讲解模式集合。
 * 方法类节点优先使用方法调用链讲解，非方法节点则依据类型映射到结构、组件或资源绑定模式。
 */
private fun allowedModesFor(
    anchor: GraphNode?,
    hasMethodCallEvidence: Boolean,
    relationKinds: List<String>,
): List<GraphExplanationMode> =
    when (anchor?.type) {
        NodeType.METHOD,
        NodeType.FLOW_ACTION,
        NodeType.FLOW_SCOPE,
        NodeType.TERMINAL,
        -> buildList {
            if (hasMethodCallEvidence || anchor.type == NodeType.METHOD || anchor.type == NodeType.FLOW_ACTION) {
                add(GraphExplanationMode.METHOD_CHAIN)
            }
            add(GraphExplanationMode.DRILLDOWN_SUGGESTION)
        }
        NodeType.PACKAGE -> listOf(GraphExplanationMode.PACKAGE_OVERVIEW, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
        NodeType.COMPONENT,
        NodeType.SERVICE,
        NodeType.LAYER,
        -> listOf(GraphExplanationMode.COMPONENT_OVERVIEW, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
        NodeType.RESOURCE,
        NodeType.SQL,
        NodeType.CONFIG_ITEM,
        NodeType.HTTP_ENDPOINT,
        -> listOf(GraphExplanationMode.RESOURCE_BINDING, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
        else -> listOf(GraphExplanationMode.STRUCTURE_OVERVIEW, GraphExplanationMode.DRILLDOWN_SUGGESTION) + relationSummaryMode(relationKinds)
    }.distinct()

/** 当存在可用关系时附带关系概览模式，否则不附加。 */
private fun relationSummaryMode(relationKinds: List<String>): List<GraphExplanationMode> =
    if (relationKinds.isEmpty()) emptyList() else listOf(GraphExplanationMode.RELATION_SUMMARY)

/**
 * 根据锚点类型与方法调用证据，输出当前场景禁止做出的声明。
 * 这些禁止项会被注入提示词，避免模型输出超出当前证据可支持的结论。
 */
private fun forbiddenClaimsFor(anchor: GraphNode?, hasMethodCallEvidence: Boolean): List<String> =
    buildList {
        if (anchor?.type !in methodLikeNodeTypes) {
            add("不要把当前锚点称为方法或当前方法")
            add("不要输出“定位被调方法”")
            add("不要生成方法调用链或业务步骤链")
        }
        if (!hasMethodCallEvidence) {
            add("没有 CALL 边或 CALLS 关系时不要断言被调方法")
        }
    }

/**
 * 汇总当前上下文中可观测到的证据缺口。
 * 这些缺口会暴露给模型与 UI，提示后续应优先补足哪一类证据。
 */
private fun evidenceGapsFor(
    anchor: GraphNode?,
    hasMethodCallEvidence: Boolean,
    incomingRelationCount: Int,
    outgoingRelationCount: Int,
): List<String> =
    buildList {
        if (anchor?.type !in methodLikeNodeTypes && !hasMethodCallEvidence) {
            add("缺少方法级调用边")
        }
        if (incomingRelationCount == 0) {
            add("缺少上游关系证据")
        }
        if (outgoingRelationCount == 0) {
            add("缺少下游关系证据")
        }
    }

/**
 * 基于当前锚点推荐可继续下钻的目标节点 ID 列表。
 * 优先返回方法类节点，再考虑类与接口节点，最多返回 8 个候选。
 */
private fun recommendedDrilldowns(
    graph: GraphDocument,
    fullGraph: GraphDocument,
    anchor: GraphNode?,
): List<String> {
    val nodes = (graph.nodes + fullGraph.nodes).distinctBy(GraphNode::id)
    val anchorId = anchor?.id ?: return emptyList()
    return nodes
        .filter { node -> node.id != anchorId && node.type in methodLikeNodeTypes + NodeType.CLASS + NodeType.INTERFACE }
        .take(8)
        .map(GraphNode::id)
}

/** 表示可被视为“方法级”的节点类型集合，用于判断是否允许方法调用链讲解。 */
private val methodLikeNodeTypes = setOf(
    NodeType.METHOD,
    NodeType.FLOW_ACTION,
    NodeType.FLOW_SCOPE,
    NodeType.TERMINAL,
)
