package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.ResultEvidenceFinding

/**
 * 校验证据是否足够直接。
 *
 * "直接证据"指与结论有强关联的证据（例如结论说方法 A 调用 B，
 * 直接证据就是 A 调用 B 的代码行）。间接证据或观察不到证据都不能通过校验。
 * 本工具让模型在给出结论前自检证据强度。
 *
 * @param validationToolFacade 实际校验逻辑的外观
 */
class ValidateEvidenceTool(
    private val validationToolFacade: ValidationToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "validate_evidence"

    /** 工具职责说明。 */
    override val description: String = "校验当前结果是否具备直接证据"

    /**
     * @param input 可选 key="findings" 的证据列表
     * @param context 工具执行上下文
     * @return payload 中带 valid 字段表示证据是否足够直接
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val findings = input.optionalList<ResultEvidenceFinding>("findings")
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "valid" to validationToolFacade.hasDirectEvidence(findings),
            ),
        )
    }
}
