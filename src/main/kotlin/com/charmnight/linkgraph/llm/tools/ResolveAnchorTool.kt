package com.charmnight.linkgraph.llm.tools

/**
 * 根据 nodeId 或 symbolSignature 解析代码锚点。
 */
class ResolveAnchorTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : AgentTool {
    override val name: String = "resolve_anchor"

    override val description: String = "根据 nodeId 或 symbolSignature 解析代码锚点"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val anchor = codeReadToolFacade.resolveAnchor(
            snapshot = context.snapshot,
            nodeId = input["nodeId"]?.toString(),
            symbolSignature = input["symbolSignature"]?.toString(),
        ) ?: return ToolResult(
            toolName = name,
            success = false,
            errorMessage = "未解析到代码锚点",
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "node" to anchor,
            ),
        )
    }
}
