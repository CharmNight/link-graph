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
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "get_node_detail"

    /** 工具职责说明。 */
    override val description: String = "按 nodeId 读取节点详情"

    /**
     * @param input 包含 key="nodeId" 的节点 ID
     * @param context 工具执行上下文，提供图快照
     * @return payload 中带 node 字段；节点不存在时返回失败结果
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val nodeId = input.requiredString("nodeId") ?: return missingRequired("nodeId")
        val node = graphToolFacade.nodeDetail(context.snapshot, nodeId)
            // 节点不存在时返回失败结果，让模型可以判断下一步
            ?: return failure("未找到节点: $nodeId")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "node" to node,
            ),
        )
    }
}
