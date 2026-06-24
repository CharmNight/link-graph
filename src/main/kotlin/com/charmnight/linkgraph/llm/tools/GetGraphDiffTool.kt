package com.charmnight.linkgraph.llm.tools

/**
 * 读取当前图 diff。
 *
 * 让模型在 QA、计划等阶段拿到当前图与基线之间的差异，
 * 不需要模型直接持有 diff 对象。结果附带节点/边变更计数，
 * 让模型一眼判断变更规模。
 *
 * @param graphToolFacade 图查询外观
 */
class GetGraphDiffTool(
    private val graphToolFacade: GraphToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "get_graph_diff"

    /** 工具职责说明。 */
    override val description: String = "读取当前图 diff 摘要"

    /**
     * @param input 工具输入（本工具不读取参数）
     * @param context 工具执行上下文，提供图快照
     * @return payload 包含完整 diff 与节点/边变更计数
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val diff = graphToolFacade.currentDiff(context.snapshot)
        // 按元素类型分别计数，让模型感知变更构成
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
