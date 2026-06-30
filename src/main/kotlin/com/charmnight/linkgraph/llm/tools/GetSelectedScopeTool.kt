package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

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
) : TypedAgentTool<GetSelectedScopeInput>() {
    override val name: String = "get_selected_scope"
    override val description: String = "读取当前选区节点范围"

    override fun parseInput(raw: ToolInputPayload): GetSelectedScopeInput = GetSelectedScopeInput(
        selectedNodeIds = optionalList(raw, "selectedNodeIds", String::class),
    )

    override fun invokeTyped(input: GetSelectedScopeInput, context: ToolExecutionContext): ToolResult {
        // 工具调用方可以传入候选 ID，再由 facade 综合考虑 UI 实际选中状态
        val selectedNodeIds = graphToolFacade.selectedNodeIds(
            snapshot = context.snapshot,
            requestedNodeIds = input.selectedNodeIds,
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

/** [GetSelectedScopeTool] 的强类型入参。selectedNodeIds 缺省时由 facade 回退到 UI 实际选中。 */
data class GetSelectedScopeInput(val selectedNodeIds: List<String>)
