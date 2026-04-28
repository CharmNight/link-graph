package com.charmnight.linkgraph.llm.tools

/**
 * 读取当前选区节点列表。
 */
class GetSelectedScopeTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    override val name: String = "get_selected_scope"

    override val description: String = "读取当前选区节点范围"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        @Suppress("UNCHECKED_CAST")
        val requestedNodeIds = input["selectedNodeIds"] as? List<String> ?: emptyList()
        val selectedNodeIds = graphToolFacade.selectedNodeIds(
            snapshot = context.snapshot,
            requestedNodeIds = requestedNodeIds,
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "selectedNodeIds" to selectedNodeIds,
                "selectedCount" to selectedNodeIds.size,
            ),
        )
    }
}
