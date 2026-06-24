package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts

/**
 * 流程图视图的统计摘要，记录节点数量、分支数量、异常路径、隐藏与不完整信息等。
 *
 * 用于在 UI 上展示"画布里有多少节点、有多少被隐藏、是否被截断、是否语义不完整"等概要信息。
 */
data class FlowchartSummary(
    /** 当前可见流程图中的节点总数。 */
    val nodeCount: Int = 0,
    /** 可见图中的决策分支数量（IF、SWITCH、循环等会被归类为 DECISION）。 */
    val branchCount: Int = 0,
    /** 可见图中的异常路径边数量。 */
    val exceptionPathCount: Int = 0,
    /** 完整流程图的节点总数，包含因裁剪而隐藏的部分。 */
    val fullNodeCount: Int = 0,
    /** 完整流程图的边总数。 */
    val fullEdgeCount: Int = 0,
    /** 因投影裁剪被隐藏的节点数量。 */
    val hiddenNodeCount: Int = 0,
    /** 因投影裁剪被隐藏的边数量。 */
    val hiddenEdgeCount: Int = 0,
    /** 是否因规模限制被截断展示。 */
    val truncated: Boolean = false,
    /** 可见图中标记为不完整的节点数。 */
    val incompleteNodeCount: Int = 0,
    /** 可见图中标记为不完整的边数。 */
    val incompleteEdgeCount: Int = 0,
    /** 是否存在任何不完整的节点或边，表示流程语义可能不完整。 */
    val semanticallyIncomplete: Boolean = false,
    /** 合成边总数，用于提示用户部分连线是推断生成的。 */
    val syntheticEdgeCount: Int = 0,
    /** 由投影层补齐的入口合成边数量，单独统计便于 UI 区分。 */
    val syntheticEntryEdgeCount: Int = 0,
)

/**
 * 流程图视图文档：包含可见图、完整图、锚点节点、统计摘要与投影索引。
 *
 * 可见图是最终展示给用户的图，完整图保留所有节点/边用于后续展开，
 * 投影索引用于把可见图的节点/边反向映射到原始语义单元。
 */
data class FlowchartViewDocument(
    /** 经过裁剪和投影处理后展示给用户的流程图。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 保留全部节点和边的完整流程图。 */
    val fullGraph: GraphDocument = GraphDocument(),
    /** 当前视图聚焦的锚点节点 ID。 */
    val anchorNodeId: String? = null,
    /** 流程图的统计摘要。 */
    val summary: FlowchartSummary = FlowchartSummary(),
    /** 可见图与完整图之间的投影索引，用于编辑命令映射。 */
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)

/** 判定为决策分支的流程作用域种类集合。 */
private val decisionFlowScopeKinds = setOf("IF", "SWITCH", "FOREACH", "FOR", "WHILE", "DO_WHILE")

/**
 * 根据节点元数据解析其在流程图中的归类（DECISION/MERGE/TERMINAL/PROCESS 等）。
 *
 * 用于统计与渲染时区分不同种类的流程节点。
 */
internal fun resolveFlowchartKind(node: GraphNode): String {
    /** 节点元数据中标注的流程种类，统一大写后用于匹配。 */
    val flowKind = node.metadata["flow.kind"]?.trim()?.uppercase()
    return when {
        node.type == NodeType.FLOW_SCOPE && flowKind in decisionFlowScopeKinds -> "DECISION"
        node.type == NodeType.MERGE -> "MERGE"
        node.type == NodeType.TERMINAL -> "TERMINAL"
        else -> node.metadata["flowchart.kind"] ?: "PROCESS"
    }
}

/**
 * 比较可见图与完整图，计算流程图视图的统计摘要。
 *
 * 包含节点/边规模、隐藏数量、合成边数量、是否被截断、是否存在不完整节点等。
 */
internal fun deriveFlowchartSummary(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): FlowchartSummary {
    /** 可见图中标记为不完整的节点数量。 */
    val incompleteNodeCount = visibleGraph.nodes.count { node -> node.metadata["flow.incomplete"] == "true" }
    /** 可见图中标记为不完整的边数量。 */
    val incompleteEdgeCount = visibleGraph.edges.count { edge -> edge.metadata["flow.incomplete"] == "true" }
    /** 可见图中所有合成边的数量。 */
    val syntheticEdgeCount = visibleGraph.edges.count { edge -> edge.metadata["flow.synthetic"] == "true" }
    /** 投影层补齐的入口合成边数量，通过 provenance 进一步过滤。 */
    val syntheticEntryEdgeCount = visibleGraph.edges.count { edge ->
        edge.metadata["flow.synthetic"] == "true" && edge.metadata["flow.provenance"] == "SYNTHETIC_PROJECTION"
    }
    /** 通过公共工具计算隐藏节点/边数与截断标记。 */
    val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
    return FlowchartSummary(
        nodeCount = visibleGraph.nodes.size,
        branchCount = visibleGraph.nodes.count { resolveFlowchartKind(it) == "DECISION" },
        exceptionPathCount = visibleGraph.edges.count { it.label?.trim()?.uppercase() == "EXCEPTION" },
        fullNodeCount = fullGraph.nodes.size,
        fullEdgeCount = fullGraph.edges.size,
        hiddenNodeCount = hiddenCounts.hiddenNodeCount,
        hiddenEdgeCount = hiddenCounts.hiddenEdgeCount,
        truncated = hiddenCounts.truncated,
        incompleteNodeCount = incompleteNodeCount,
        incompleteEdgeCount = incompleteEdgeCount,
        semanticallyIncomplete = incompleteNodeCount > 0 || incompleteEdgeCount > 0,
        syntheticEdgeCount = syntheticEdgeCount,
        syntheticEntryEdgeCount = syntheticEntryEdgeCount,
    )
}
