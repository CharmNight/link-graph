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
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "validate_edit_scope"

    /** 工具职责说明。 */
    override val description: String = "校验 existing-file 草稿是否具备合法 edit scope"

    /**
     * @param input 包含 key="draft" 的草稿对象
     * @param context 工具执行上下文
     * @return payload 中带 valid 字段表示 edit scope 是否合法
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val draft = input.requiredValue<GeneratedCodeDraft>("draft") ?: return missingRequired("draft")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                // 以项目根路径作为基准判断 edit scope 是否合法
                "valid" to validationToolFacade.hasValidEditScope(draft, context.project.basePath),
            ),
        )
    }
}
