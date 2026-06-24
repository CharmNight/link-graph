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
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "expand_graph_neighborhood"

    /** 工具职责说明。 */
    override val description: String = "展开指定节点的一跳或多跳邻域"

    /**
     * @param input 必须包含 key="nodeId"，可选 key="depth"
     * @param context 工具执行上下文
     * @return payload 包含展开后的子图与规模统计
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val nodeId = input.requiredString("nodeId") ?: return missingRequired("nodeId")
        // depth 默认 1，至少为 1（避免传入 0 或负数导致空结果）
        val depth = input.optionalInt("depth") ?: 1
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
