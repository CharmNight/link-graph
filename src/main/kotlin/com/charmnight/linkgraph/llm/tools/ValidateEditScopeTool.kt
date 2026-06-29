package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft

/**
 * 校验 existing-file 草稿是否具备合法 edit scope。
 *
 * "Edit scope" 描述草稿允许修改的代码范围。existing-file 草稿只能改它声明的范围，
 * 不能越权改其他位置。本工具让模型在写盘前自检，避免后期被拒。
 *
 * @param validationToolFacade 实际校验逻辑的外观
 */
class ValidateEditScopeTool(
    private val validationToolFacade: ValidationToolFacade,
) : TypedAgentTool<ValidateEditScopeInput>() {
    override val name: String = "validate_edit_scope"
    override val description: String = "校验 existing-file 草稿是否具备合法 edit scope"

    override fun parseInput(raw: Map<String, Any?>): ValidateEditScopeInput = ValidateEditScopeInput(
        draft = requireValue(raw, "draft", GeneratedCodeDraft::class),
    )

    override fun invokeTyped(input: ValidateEditScopeInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf(
                // 以项目根路径作为基准判断 edit scope 是否合法
                "valid" to validationToolFacade.hasValidEditScope(input.draft, context.project.basePath),
            ),
        )
}

/** [ValidateEditScopeTool] 的强类型入参。 */
data class ValidateEditScopeInput(val draft: GeneratedCodeDraft)
