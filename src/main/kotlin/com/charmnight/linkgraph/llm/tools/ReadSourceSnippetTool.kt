package com.charmnight.linkgraph.llm.tools

/**
 * 读取源码片段。
 */
class ReadSourceSnippetTool(
    private val codeReadToolFacade: CodeReadToolFacade,
) : AgentTool {
    override val name: String = "read_source_snippet"

    override val description: String = "读取指定文件的源码片段"

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
                "decompiled" to snippet.decompiled,
                "virtualFileUrl" to snippet.virtualFileUrl,
                "sourceDiagnostic" to snippet.sourceDiagnostic,
            ),
        )
    }
}
