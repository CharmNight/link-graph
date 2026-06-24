package com.charmnight.linkgraph.llm.tools

/**
 * 根据 nodeId 或 symbolSignature 解析代码锚点。
 *
 * 锚点是"模型当前应该关注的代码位置"。模型可能只有符号签名（例如方法全名），
 * 也可能只有 nodeId；本工具把这两种输入都尝试解析为具体的节点 + 源码位置。
 *
 * @param codeReadToolFacade 代码读取外观
 */
class ResolveAnchorTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "resolve_anchor"

    /** 工具职责说明。 */
    override val description: String = "根据 nodeId 或 symbolSignature 解析代码锚点"

    /**
     * @param input 可选 key="nodeId" 或 "symbolSignature"
     * @param context 工具执行上下文
     * @return payload 包含解析后的节点；解析失败时返回失败结果并附带 resolution 上下文
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val resolution = codeReadToolFacade.resolveEvidenceAnchor(
            snapshot = context.snapshot,
            nodeId = input.optionalString("nodeId"),
            symbolSignature = input.optionalString("symbolSignature"),
        )
        // 解析不到节点时返回失败结果，附带 resolution 让模型理解为什么失败
        val anchor = resolution.node ?: return failure(
            errorMessage = "未解析到代码锚点",
            payload = mapOf("resolution" to resolution),
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "node" to anchor,
                "resolution" to resolution,
            ),
        )
    }
}
