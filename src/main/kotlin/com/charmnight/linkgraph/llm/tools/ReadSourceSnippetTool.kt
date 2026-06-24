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
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "read_source_snippet"

    /** 工具职责说明。 */
    override val description: String = "读取指定文件的源码片段"

    /**
     * @param input 必须包含 key="filePath"；可选 startLine/endLine/fallbackSnippet
     * @param context 工具执行上下文
     * @return payload 包含文件路径、行号、片段内容、来源与诊断信息
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val filePath = input.requiredString("filePath") ?: return missingRequired("filePath")
        val startLine = input.optionalInt("startLine")
        val endLine = input.optionalInt("endLine")
        val snippet = codeReadToolFacade.readSourceSnippetRich(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            fallbackSnippet = input.optionalString("fallbackSnippet"),
            projectBasePath = context.project.basePath,
            project = context.project,
        ) ?: return failure(
            // 读取失败时附带失败原因，让模型可以判断是路径错、文件不存在还是其他原因
            "未读取到源码片段",
            payload = mapOf(
                "sourceUnavailableReason" to codeReadToolFacade.readSourceSnippetFailureReason(
                    filePath = filePath,
                    startLine = startLine,
                    endLine = endLine,
                    projectBasePath = context.project.basePath,
                    project = context.project,
                ),
            ),
        )
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "filePath" to filePath,
                "startLine" to startLine,
                "endLine" to endLine,
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
