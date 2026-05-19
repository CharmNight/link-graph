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
        val snippet = codeReadToolFacade.readSymbolRich(
            snapshot = context.snapshot,
            symbolSignature = symbolSignature,
            fallbackSourceContexts = fallbackSourceContexts,
            projectBasePath = context.project.basePath,
            project = context.project,
        ) ?: if (shouldReadByQualifiedName(symbolSignature)) {
            codeReadToolFacade.readSymbolByQualifiedNameRich(
                symbolSignature = symbolSignature,
                project = context.project,
            )
        } else {
            null
        }
            ?: return failure(
                "未读取到 symbol 对应源码",
                payload = mapOf(
                    "sourceUnavailableReason" to codeReadToolFacade.readSymbolFailureReason(
                        symbolSignature = symbolSignature,
                        project = context.project,
                        projectBasePath = context.project.basePath,
                    ),
                ),
            )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "sourceSnippetContext" to snippet.toSourceSnippetContext(),
                "origin" to snippet.origin,
                "decompiled" to snippet.decompiled,
                "virtualFileUrl" to snippet.virtualFileUrl,
                "sourceDiagnostic" to snippet.sourceDiagnostic,
                "symbolId" to snippet.symbolId.orEmpty().ifBlank { snippet.nodeId },
            ),
        )
    }

    private fun shouldReadByQualifiedName(symbolSignature: String): Boolean {
        val trimmed = symbolSignature.trim()
        if (trimmed.isBlank()) {
            return false
        }
        if (trimmed.contains("(") || trimmed.contains("):") || trimmed.contains("#")) {
            return false
        }
        return trimmed.startsWith("META-INF/services/") ||
            trimmed.endsWith(".java") ||
            trimmed.endsWith(".kt") ||
            trimmed.matches(Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+"""))
    }
}
