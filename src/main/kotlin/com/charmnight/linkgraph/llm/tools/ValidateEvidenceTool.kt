package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.ResultEvidenceFinding

/**
 * 校验证据是否足够直接。
 */
class ValidateEvidenceTool(
    private val validationToolFacade: ValidationToolFacade,
) : AgentTool {
    override val name: String = "validate_evidence"

    override val description: String = "校验当前结果是否具备直接证据"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        @Suppress("UNCHECKED_CAST")
        val findings = input["findings"] as? List<ResultEvidenceFinding> ?: emptyList()
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "valid" to validationToolFacade.hasDirectEvidence(findings),
            ),
        )
    }
}
