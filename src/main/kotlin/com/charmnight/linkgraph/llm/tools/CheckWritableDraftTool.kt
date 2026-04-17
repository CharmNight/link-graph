package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft

/**
 * 检查草稿是否满足最小写回条件。
 */
class CheckWritableDraftTool(
    private val validationToolFacade: ValidationToolFacade,
) : AgentTool {
    override val name: String = "check_writable_draft"

    override val description: String = "检查草稿是否满足最小写回条件"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val draft = input["draft"] as? GeneratedCodeDraft
            ?: return ToolResult(toolName = name, success = false, errorMessage = "draft 不能为空")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "writable" to validationToolFacade.isWritableDraft(draft),
            ),
        )
    }
}
