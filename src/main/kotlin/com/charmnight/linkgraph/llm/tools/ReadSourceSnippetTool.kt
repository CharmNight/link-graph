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
        val filePath = input["filePath"]?.toString()
        if (filePath.isNullOrBlank()) {
            return ToolResult(toolName = name, success = false, errorMessage = "filePath 不能为空")
        }
        val startLine = (input["startLine"] as? Number)?.toInt()
        val endLine = (input["endLine"] as? Number)?.toInt()
        val snippet = codeReadToolFacade.readSourceSnippet(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            fallbackSnippet = input["fallbackSnippet"]?.toString(),
            projectBasePath = context.project.basePath,
        ) ?: return ToolResult(toolName = name, success = false, errorMessage = "未读取到源码片段")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "filePath" to filePath,
                "startLine" to startLine,
                "endLine" to endLine,
                "snippet" to snippet,
            ),
        )
    }
}
