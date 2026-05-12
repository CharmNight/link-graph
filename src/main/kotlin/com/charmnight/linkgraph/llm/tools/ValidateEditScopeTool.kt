package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft

/**
 * 校验 existing-file 草稿是否具备合法 edit scope。
 */
class ValidateEditScopeTool(
    private val validationToolFacade: ValidationToolFacade,
) : AgentTool {
    override val name: String = "validate_edit_scope"

    override val description: String = "校验 existing-file 草稿是否具备合法 edit scope"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val draft = input.requiredValue<GeneratedCodeDraft>("draft") ?: return missingRequired("draft")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "valid" to validationToolFacade.hasValidEditScope(draft, context.project.basePath),
            ),
        )
    }
}
