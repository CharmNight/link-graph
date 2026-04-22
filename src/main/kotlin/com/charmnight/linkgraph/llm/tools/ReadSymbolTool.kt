package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.SourceSnippetContext

/**
 * 根据 symbolSignature 读取关联源码片段。
 */
class ReadSymbolTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : AgentTool {
    override val name: String = "read_symbol"

    override val description: String = "按 symbolSignature 读取关联源码片段"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val symbolSignature = input["symbolSignature"]?.toString()
        if (symbolSignature.isNullOrBlank()) {
            return ToolResult(toolName = name, success = false, errorMessage = "symbolSignature 不能为空")
        }
        @Suppress("UNCHECKED_CAST")
        val fallbackSourceContexts = input["fallbackSourceContexts"] as? List<SourceSnippetContext> ?: emptyList()
        val snippet = codeReadToolFacade.readSymbol(
            snapshot = context.snapshot,
            symbolSignature = symbolSignature,
            fallbackSourceContexts = fallbackSourceContexts,
            projectBasePath = context.project.basePath,
        ) ?: return ToolResult(toolName = name, success = false, errorMessage = "未读取到 symbol 对应源码")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "sourceSnippetContext" to snippet,
            ),
        )
    }
}
