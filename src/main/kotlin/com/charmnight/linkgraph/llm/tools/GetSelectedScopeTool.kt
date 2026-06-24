package com.charmnight.linkgraph.llm.tools

/**
 * 读取当前选区节点列表。
 *
 * 让模型按需取得当前 UI 选中的节点，或回退到调用方传入的候选列表。
 * 选区是模型理解"用户当前关注什么"的重要线索。
 *
 * @param graphToolFacade 图查询外观
 */
class GetSelectedScopeTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "get_selected_scope"

    /** 工具职责说明。 */
    override val description: String = "读取当前选区节点范围"

    /**
     * @param input 可选 key="selectedNodeIds" 作为候选
     * @param context 工具执行上下文
     * @return payload 包含最终确定的选中节点 ID 列表与计数
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        // 工具调用方可以传入候选 ID，再由 facade 综合考虑 UI 实际选中状态
        val requestedNodeIds = input.optionalList<String>("selectedNodeIds")
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
