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
        val symbolSignature = input.requiredString("symbolSignature") ?: return missingRequired("symbolSignature")
        val fallbackSourceContexts = input.optionalList<SourceSnippetContext>("fallbackSourceContexts")
        val snippet = codeReadToolFacade.readSymbol(
            snapshot = context.snapshot,
            symbolSignature = symbolSignature,
            fallbackSourceContexts = fallbackSourceContexts,
            projectBasePath = context.project.basePath,
            project = context.project,
        ) ?: return failure("未读取到 symbol 对应源码")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "sourceSnippetContext" to snippet,
            ),
        )
    }
}
