package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.extract.ExtractionBoundary
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType

/**
 * 在“当前方法图”只定位到边界而未能继续展开时，补充边界提示节点。
 */
internal object CurrentMethodBoundaryMarker {
    /**
     * 仅在图中只有锚点方法节点时，为其追加边界说明节点和连边。
     */
    fun appendIfNeeded(
        graph: GraphDocument,
        methodSignature: String,
        methodDisplayName: String,
        boundary: ExtractionBoundary?,
    ): GraphDocument {
        // 没有边界信息时保持原图不变。
        if (boundary == null) {
            return graph
        }

        // 只有“单方法锚点图”才追加边界提示，避免污染正常提图结果。
        val anchorNodeId = GraphNode.stableId(NodeType.METHOD, methodSignature)
        if (graph.nodes.size != 1 || graph.edges.isNotEmpty() || graph.nodes.singleOrNull()?.id != anchorNodeId) {
            return graph
        }

        // 构造一个显式的“未完整展开”提示节点，引导用户继续人工处理或围绕边界继续问答。
        val boundaryNode = GraphNode(
            id = GraphNode.stableId(
                NodeType.UNCERTAIN_LINK,
                "$methodSignature-${boundary.kind}",
                "current-method-boundary",
            ),
            type = NodeType.UNCERTAIN_LINK,
            title = boundary.title,
            doc = "当前方法已成功定位，但静态链路提取在这里遇到了明确边界。请将该节点视为“链路未完整展开”的显式标识，再决定是否人工补图或交给 AI 基于该边界继续问答。",
            signature = boundary.reason,
            certainty = Certainty.RULE_INFERRED,
            bindingStatus = BindingStatus.PARTIALLY_SYNCED,
            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
            metadata = mapOf(
                "linkGraph.boundary.kind" to boundary.kind,
                "linkGraph.boundary.methodDisplayName" to methodDisplayName,
            ),
        )
        // 从方法节点连到边界提示节点，明确指出静态提取在此终止。
        val boundaryEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, anchorNodeId, boundaryNode.id, "current-method-boundary"),
            type = EdgeType.CALL,
            fromNodeId = anchorNodeId,
            toNodeId = boundaryNode.id,
            label = "静态提取边界",
            certainty = Certainty.RULE_INFERRED,
            bindingStatus = BindingStatus.PARTIALLY_SYNCED,
            sourceTag = GraphSourceTag.UNCERTAIN_FACT,
        )
        // 返回追加了边界节点与边的新图。
        return graph.copy(
            nodes = graph.nodes + boundaryNode,
            edges = graph.edges + boundaryEdge,
        )
    }
}
