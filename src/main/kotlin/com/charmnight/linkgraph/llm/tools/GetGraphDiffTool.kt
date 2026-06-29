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
) : TypedAgentTool<GetGraphDiffInput>() {
    override val name: String = "get_graph_diff"
    override val description: String = "读取当前图 diff 摘要"

    override fun parseInput(raw: Map<String, Any?>): GetGraphDiffInput = GetGraphDiffInput

    override fun invokeTyped(input: GetGraphDiffInput, context: ToolExecutionContext): ToolResult {
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

/** [GetGraphDiffTool] 的入参（工具不接受任何参数，用 object 表达）。 */
object GetGraphDiffInput
