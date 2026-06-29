package com.charmnight.linkgraph.llm.tools

/**
 * 读取源码片段。
 *
 * 让模型按需读取具体文件的代码片段，作为推理与生成的事实依据。
 * 支持 fallbackSnippet：当本地源码不可读（例如外部 jar）时，
 * 模型可以从已知上下文提供候选内容，避免推理被中断。
 *
 * @param codeReadToolFacade 代码读取外观，封装实际读取逻辑
 */
class ReadSourceSnippetTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : TypedAgentTool<ReadSourceSnippetInput>() {
    override val name: String = "read_source_snippet"
    override val description: String = "读取指定文件的源码片段"

    override fun parseInput(raw: Map<String, Any?>): ReadSourceSnippetInput = ReadSourceSnippetInput(
        filePath = requireString(raw, "filePath"),
        startLine = optionalInt(raw, "startLine"),
        endLine = optionalInt(raw, "endLine"),
        fallbackSnippet = optionalString(raw, "fallbackSnippet"),
    )

    override fun invokeTyped(input: ReadSourceSnippetInput, context: ToolExecutionContext): ToolResult {
        val snippet = codeReadToolFacade.readSourceSnippetRich(
            filePath = input.filePath,
            startLine = input.startLine,
            endLine = input.endLine,
            fallbackSnippet = input.fallbackSnippet,
            projectBasePath = context.project.basePath,
            project = context.project,
        ) ?: return failure(
            // 读取失败时附带失败原因，让模型可以判断是路径错、文件不存在还是其他原因
            "未读取到源码片段",
            payload = mapOf(
                "sourceUnavailableReason" to codeReadToolFacade.readSourceSnippetFailureReason(
                    filePath = input.filePath,
                    startLine = input.startLine,
                    endLine = input.endLine,
                    projectBasePath = context.project.basePath,
                    project = context.project,
                ),
            ),
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "filePath" to input.filePath,
                "startLine" to input.startLine,
                "endLine" to input.endLine,
                "snippet" to snippet.snippet,
                "origin" to snippet.origin,
                // 是否来自反编译，让模型知道这段代码可能不精确
                "decompiled" to snippet.decompiled,
                "virtualFileUrl" to snippet.virtualFileUrl,
                "sourceDiagnostic" to snippet.sourceDiagnostic,
            ),
        )
    }
}

/** [ReadSourceSnippetTool] 的强类型入参。 */
data class ReadSourceSnippetInput(
    val filePath: String,
    val startLine: Int?,
    val endLine: Int?,
    val fallbackSnippet: String?,
)
