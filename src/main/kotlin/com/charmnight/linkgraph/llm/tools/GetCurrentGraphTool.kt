package com.charmnight.linkgraph.llm.tools

/**
 * 返回当前 runtime 可读取的工作图摘要。
 *
 * 让模型一次拿到工作所需的全部上下文（图、来源、版本、推荐场景、规模、选区），
 * 而不是分别调用多个工具。这种聚合在多步流程的开头特别有用。
 *
 * @param graphToolFacade 图查询外观
 */
class GetCurrentGraphTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "get_current_graph"

    /** 工具职责说明。 */
    override val description: String = "读取当前工作图及其基础摘要"

    /**
     * @param input 工具输入（本工具不读取参数）
     * @param context 工具执行上下文，提供图快照
     * @return payload 包含图、来源、版本、场景、规模与选区
     */
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
                // 推荐场景 ID 让模型知道当前应使用哪个场景的视角
                "recommendedSceneId" to context.snapshot.currentSceneId.name,
                "nodeCount" to graph.nodes.size,
                "edgeCount" to graph.edges.size,
                "selectedNodeIds" to selectedNodeIds,
            ),
        )
    }
}
