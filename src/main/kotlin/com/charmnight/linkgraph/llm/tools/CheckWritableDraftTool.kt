package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft

/**
 * 检查草稿是否满足最小写回条件。
 *
 * 在真正写盘之前调用本工具，让模型自己判断"这个草稿能不能写"，
 * 例如必填字段是否齐全、文件路径是否合法等。
 * 工具只返回布尔结果，不做修改。
 *
 * @param validationToolFacade 实际校验逻辑的外观，便于替换实现或在测试中桩
 */
class CheckWritableDraftTool(
    private val validationToolFacade: ValidationToolFacade,
) : AgentTool {
    /** 工具稳定名称，模型按此名称调用。 */
    override val name: String = "check_writable_draft"

    /** 工具职责说明，进入模型提示用。 */
    override val description: String = "检查草稿是否满足最小写回条件"

    /**
     * @param input 包含 key="draft" 的草稿对象
     * @param context 工具执行上下文
     * @return payload 中带 writable 字段表示是否可写
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        // 必须传入 draft 字段，否则返回标准化的缺失错误
        val draft = input.requiredValue<GeneratedCodeDraft>("draft") ?: return missingRequired("draft")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "writable" to validationToolFacade.isWritableDraft(draft),
            ),
        )
    }
}
