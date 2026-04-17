package com.charmnight.linkgraph.llm.tools

/**
 * 统一定义 Agent 可调用工具。
 * Tool 只暴露受控读取能力，不允许直接写 UI、确认草稿或写源码。
 */
interface AgentTool {
    /** 工具稳定名称。 */
    val name: String

    /** 工具职责说明。 */
    val description: String

    /** 执行工具。 */
    fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult
}
