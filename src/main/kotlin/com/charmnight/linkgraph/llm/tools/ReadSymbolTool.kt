package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.SourceSnippetContext

/**
 * 根据 symbolSignature 读取关联源码片段。
 *
 * 与 [ReadSourceSnippetTool] 区别：本工具的输入是"符号签名"而不是"文件路径 + 行号"，
 * 让模型可以按符号（方法签名、全限定类名等）查询源码。
 *
 * 当按签名查不到时，会根据签名形态判断是否适合用全限定名再查一次（兜底）。
 *
 * @param codeReadToolFacade 代码读取外观
 */
class ReadSymbolTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : TypedAgentTool<ReadSymbolInput>() {
    override val name: String = "read_symbol"
    override val description: String = "按 symbolSignature 读取关联源码片段"

    override fun parseInput(raw: Map<String, Any?>): ReadSymbolInput = ReadSymbolInput(
        symbolSignature = requireString(raw, "symbolSignature"),
        fallbackSourceContexts = optionalList(raw, "fallbackSourceContexts", SourceSnippetContext::class),
    )

    override fun invokeTyped(input: ReadSymbolInput, context: ToolExecutionContext): ToolResult {
        // 先按签名查
        val snippet = codeReadToolFacade.readSymbolRich(
            snapshot = context.snapshot,
            symbolSignature = input.symbolSignature,
            fallbackSourceContexts = input.fallbackSourceContexts,
            projectBasePath = context.project.basePath,
            project = context.project,
        ) ?: if (shouldReadByQualifiedName(input.symbolSignature)) {
            // 签名看起来像全限定名时，按全限定名兜底查一次
            codeReadToolFacade.readSymbolByQualifiedNameRich(
                symbolSignature = input.symbolSignature,
                project = context.project,
            )
        } else {
            null
        }
            ?: return failure(
                "未读取到 symbol 对应源码",
                payload = mapOf(
                    "sourceUnavailableReason" to codeReadToolFacade.readSymbolFailureReason(
                        symbolSignature = input.symbolSignature,
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
                // 是否反编译：让模型知道代码可能不精确
                "decompiled" to snippet.decompiled,
                "virtualFileUrl" to snippet.virtualFileUrl,
                "sourceDiagnostic" to snippet.sourceDiagnostic,
                // symbolId 缺失时用 nodeId 兜底
                "symbolId" to snippet.symbolId.orEmpty().ifBlank { snippet.nodeId },
            ),
        )
    }

    /**
     * 判断签名是否适合按全限定名查询。
     *
     * 排除：方法签名（含括号）、文件路径（含 / 或 #）。
     * 保留：META-INF/services 路径、.java/.kt 文件名、点号分隔的类全限定名。
     */
    private fun shouldReadByQualifiedName(symbolSignature: String): Boolean {
        val trimmed = symbolSignature.trim()
        if (trimmed.isBlank()) {
            return false
        }
        // 含括号或路径分隔符：不是全限定名
        if (trimmed.contains("(") || trimmed.contains("):") || trimmed.contains("#")) {
            return false
        }
        return trimmed.startsWith("META-INF/services/") ||
            trimmed.endsWith(".java") ||
            trimmed.endsWith(".kt") ||
            // 标准的 Java/Kotlin 全限定名形态
            trimmed.matches(Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+"""))
    }
}

/** [ReadSymbolTool] 的强类型入参。fallbackSourceContexts 缺省为空。 */
data class ReadSymbolInput(
    val symbolSignature: String,
    val fallbackSourceContexts: List<SourceSnippetContext>,
)
