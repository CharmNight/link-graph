package com.charmnight.linkgraph.llm.tools

/**
 * 展开指定节点的邻域子图。
 */
class ExpandGraphNeighborhoodTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    override val name: String = "expand_graph_neighborhood"

    override val description: String = "展开指定节点的一跳或多跳邻域"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val nodeId = input["nodeId"]?.toString()
        val depth = (input["depth"] as? Number)?.toInt() ?: 1
        if (nodeId.isNullOrBlank()) {
            return ToolResult(
                toolName = name,
                success = false,
                errorMessage = "nodeId 不能为空",
            )
        }
        val graph = graphToolFacade.expandNeighborhood(
            snapshot = context.snapshot,
            nodeId = nodeId,
            depth = depth.coerceAtLeast(1),
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "graph" to graph,
                "nodeCount" to graph.nodes.size,
                "edgeCount" to graph.edges.size,
            ),
        )
    }
}
