package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

typealias ToolInputPayload = Map<String, @JvmSuppressWildcards Any?>

typealias ToolPayload = Map<String, @JvmSuppressWildcards Any?>

/**
 * 统一定义 Agent 可调用工具。
 *
 * Tool 只暴露受控读取能力，不允许直接写 UI、确认草稿或写源码。
 * 这种约束让 runtime 可以放心地把工具暴露给模型，不必担心副作用越界。
 */
interface AgentTool {
    /** 工具稳定名称。模型在工具调用中按此名称引用，不可随版本变更。 */
    val name: String

    /** 工具职责说明。会进入模型提示，影响模型对工具的选择。 */
    val description: String

    /**
     * 执行工具。
     *
     * @param input 模型传入的参数映射；具体键由工具自定义
     * @param context 运行时上下文，提供工具可能需要的项目状态、读取 API 等
     * @return 工具执行结果，包含成功标志与结构化 payload
     */
    fun invoke(
        input: ToolInputPayload,
        context: ToolExecutionContext,
    ): ToolResult
}
