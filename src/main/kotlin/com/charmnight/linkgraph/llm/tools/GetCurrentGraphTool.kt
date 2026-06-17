package com.charmnight.linkgraph.llm.tools

/**
 * 返回当前 runtime 可读取的工作图摘要。
 */
class GetCurrentGraphTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    override val name: String = "get_current_graph"

    override val description: String = "读取当前工作图及其基础摘要"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val graph = graphToolFacade.currentGraph(context.snapshot)
        val selectedNodeIds = graphToolFacade.selectedNodeIds(context.snapshot)
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "graph" to graph,
                "graphSource" to graphToolFacade.currentGraphSource(context.snapshot),
                "workspaceRevision" to context.snapshot.workspaceRevision,
                "recommendedSceneId" to context.snapshot.currentSceneId.name,
                "nodeCount" to graph.nodes.size,
                "edgeCount" to graph.edges.size,
                "selectedNodeIds" to selectedNodeIds,
            ),
        )
    }
}
