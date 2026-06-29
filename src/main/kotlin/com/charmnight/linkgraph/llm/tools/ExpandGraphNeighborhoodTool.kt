package com.charmnight.linkgraph.llm.tools

/**
 * 展开指定节点的邻域子图。
 *
 * 让模型按需"看到"目标节点的 1~N 跳邻居，避免一次性把整张图塞进上下文。
 * 默认展开深度为 1（直接邻居），可通过 depth 参数放大。
 *
 * @param graphToolFacade 图查询外观
 */
class ExpandGraphNeighborhoodTool(
    private val graphToolFacade: GraphToolFacade,
) : TypedAgentTool<ExpandGraphNeighborhoodInput>() {
    override val name: String = "expand_graph_neighborhood"
    override val description: String = "展开指定节点的一跳或多跳邻域"

    override fun parseInput(raw: Map<String, Any?>): ExpandGraphNeighborhoodInput = ExpandGraphNeighborhoodInput(
        nodeId = requireString(raw, "nodeId"),
        depth = optionalInt(raw, "depth") ?: 1,
    )

    override fun invokeTyped(input: ExpandGraphNeighborhoodInput, context: ToolExecutionContext): ToolResult {
        val graph = graphToolFacade.expandNeighborhood(
            snapshot = context.snapshot,
            nodeId = input.nodeId,
            // depth 至少为 1，避免传入 0 或负数导致空结果
            depth = input.depth.coerceAtLeast(1),
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

/** [ExpandGraphNeighborhoodTool] 的强类型入参。depth 缺省为 1。 */
data class ExpandGraphNeighborhoodInput(
    val nodeId: String,
    val depth: Int,
)
