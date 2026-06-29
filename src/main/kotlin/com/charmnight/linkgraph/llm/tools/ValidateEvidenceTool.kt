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
) : TypedAgentTool<ValidateEvidenceInput>() {
    override val name: String = "validate_evidence"
    override val description: String = "校验当前结果是否具备直接证据"

    override fun parseInput(raw: Map<String, Any?>): ValidateEvidenceInput = ValidateEvidenceInput(
        findings = optionalList(raw, "findings", ResultEvidenceFinding::class),
    )

    override fun invokeTyped(input: ValidateEvidenceInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("valid" to validationToolFacade.hasDirectEvidence(input.findings)),
        )
}

/** [ValidateEvidenceTool] 的强类型入参；findings 缺省为空列表。 */
data class ValidateEvidenceInput(val findings: List<ResultEvidenceFinding>)
