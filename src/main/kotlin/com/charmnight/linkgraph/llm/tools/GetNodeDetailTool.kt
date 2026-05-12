package com.charmnight.linkgraph.llm.tools

/**
 * 读取指定节点详情。
 */
class GetNodeDetailTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    override val name: String = "get_node_detail"

    override val description: String = "按 nodeId 读取节点详情"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val nodeId = input.requiredString("nodeId") ?: return missingRequired("nodeId")
        val node = graphToolFacade.nodeDetail(context.snapshot, nodeId)
            ?: return failure("未找到节点: $nodeId")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "node" to node,
            ),
        )
    }
}
