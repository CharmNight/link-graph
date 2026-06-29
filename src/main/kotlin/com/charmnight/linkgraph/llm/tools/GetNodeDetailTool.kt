package com.charmnight.linkgraph.llm.tools

/**
 * 读取指定节点详情。
 *
 * 工具暴露给模型的"按 ID 拿节点"能力。模型在做问答、生成计划等任务时
 * 可能只有节点 ID 列表，需要通过本工具拿到节点的完整字段（标题、签名、元数据等）。
 *
 * @param graphToolFacade 图查询外观，封装了对快照的只读访问
 */
class GetNodeDetailTool(
    private val graphToolFacade: GraphToolFacade,
) : TypedAgentTool<GetNodeDetailInput>() {
    override val name: String = "get_node_detail"
    override val description: String = "按 nodeId 读取节点详情"

    override fun parseInput(raw: Map<String, Any?>): GetNodeDetailInput = GetNodeDetailInput(
        nodeId = requireString(raw, "nodeId"),
    )

    override fun invokeTyped(input: GetNodeDetailInput, context: ToolExecutionContext): ToolResult {
        val node = graphToolFacade.nodeDetail(context.snapshot, input.nodeId)
            // 节点不存在时返回失败结果，让模型可以判断下一步
            ?: return failure("未找到节点: ${input.nodeId}")
        return ToolResult(
            toolName = name,
            payload = mapOf("node" to node),
        )
    }
}

/** [GetNodeDetailTool] 的强类型入参。 */
data class GetNodeDetailInput(val nodeId: String)
