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
) : TypedAgentTool<CheckWritableDraftInput>() {
    override val name: String = "check_writable_draft"
    override val description: String = "检查草稿是否满足最小写回条件"

    override fun parseInput(raw: Map<String, Any?>): CheckWritableDraftInput = CheckWritableDraftInput(
        draft = requireValue(raw, "draft", GeneratedCodeDraft::class),
    )

    override fun invokeTyped(input: CheckWritableDraftInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("writable" to validationToolFacade.isWritableDraft(input.draft)),
        )
}

/** [CheckWritableDraftTool] 的强类型入参。 */
data class CheckWritableDraftInput(val draft: GeneratedCodeDraft)
