package com.charmnight.linkgraph.llm.tools

/**
 * 读取当前图 diff。
 */
class GetGraphDiffTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    override val name: String = "get_graph_diff"

    override val description: String = "读取当前图 diff 摘要"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val diff = graphToolFacade.currentDiff(context.snapshot)
        val nodeChanges = diff.entries.count { it.elementKind.name == "NODE" }
        val edgeChanges = diff.entries.count { it.elementKind.name == "EDGE" }
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "diff" to diff,
                "nodeChanges" to nodeChanges,
                "edgeChanges" to edgeChanges,
            ),
        )
    }
}
